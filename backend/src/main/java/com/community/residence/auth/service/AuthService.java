package com.community.residence.auth.service;

import com.community.residence.auth.dto.LoginDTO;
import com.community.residence.auth.util.JwtUtil;
import com.community.residence.auth.vo.LoginVO;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.UnauthorizedException;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 认证服务：双端点登录（居民 resident 表 / 管理端 sys_user 表），
 * 两类账号分表存储，端点隔离防止居民令牌进管理端、后台账号进居民端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final ResidentMapper residentMapper;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final AdminCommunityCacheService adminCommunityCacheService;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;

    /** 管理端登录：SUPER_ADMIN/ADMIN/STAFF（sys_user 表），ADMIN 加载绑定社区 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LoginVO adminLogin(LoginDTO dto) {
        var candidates = sysUserMapper.selectLoginCandidates(dto.getUsername().trim());
        if (candidates.size() != 1) throw new UnauthorizedException("用户名/手机号或密码错误");
        SysUser user = candidates.get(0);
        /* 登录失败统一措辞（不区分账号不存在/密码错误，防账号枚举）；账号存在但被冻结时明确提示 */
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("用户名/手机号或密码错误");
        }
        requireActive(user.getStatus());

        Set<Long> communityIds = RoleConstants.ADMIN.equals(user.getRole())
                ? adminCommunityCacheService.getCommunityIds(user.getId())
                : Set.of();

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), tokenRevocationService.currentRevision(user.getId(), user.getRole()));
        return buildLoginVO(token, user.getId(), user.getUsername(), user.getRealName(), user.getRole(), communityIds);
    }

    /** 居民端登录（resident 表），登录即视为 RESIDENT 角色 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LoginVO residentLogin(LoginDTO dto) {
        var candidates = residentMapper.selectLoginCandidates(dto.getUsername().trim());
        if (candidates.size() != 1) throw new UnauthorizedException("用户名/手机号或密码错误");
        Resident resident = candidates.get(0);
        if (resident == null || !passwordEncoder.matches(dto.getPassword(), resident.getPasswordHash())) {
            throw new UnauthorizedException("用户名/手机号或密码错误");
        }
        requireActive(resident.getStatus());

        String token = jwtUtil.generateToken(resident.getId(), resident.getUsername(), RoleConstants.RESIDENT, tokenRevocationService.currentRevision(resident.getId(), RoleConstants.RESIDENT));
        return buildLoginVO(token, resident.getId(), resident.getUsername(),
                resident.getRealName(), RoleConstants.RESIDENT, Set.of());
    }

    /* 续期只能由仍有效、未登出/撤权的会话发起；重新读取账号状态，不接收客户端账号信息。
       jti/iat/权限代次不轮换，避免旧请求或多标签页使同一会话的吊销出现缺口。 */
    @Transactional(readOnly = true)
    public LoginVO renew(String token) {
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null || claims.getId() == null || tokenBlacklistService.isBlacklisted(claims.getId()))
            throw new UnauthorizedException("会话已失效，请重新登录");
        Long id = jwtUtil.extractUserId(claims);
        String role = jwtUtil.extractRole(claims);
        Object value = claims.get("permissionRevision");
        if (value != null && !(value instanceof Integer || value instanceof Long))
            throw new UnauthorizedException("会话已失效，请重新登录");
        Long revision = value == null ? null : ((Number)value).longValue();
        if (tokenRevocationService.isTokenRevoked(id, role, claims.getIssuedAt(), revision))
            throw new UnauthorizedException("权限已变更，请重新登录");
        if (RoleConstants.RESIDENT.equals(role)) {
            Resident resident = residentMapper.selectById(id);
            if (resident == null) throw new UnauthorizedException("会话已失效，请重新登录");
            requireActive(resident.getStatus());
            return buildLoginVO(jwtUtil.renewToken(claims), id, resident.getUsername(), resident.getRealName(), role, Set.of());
        }
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || !role.equals(user.getRole())) throw new UnauthorizedException("会话已失效，请重新登录");
        requireActive(user.getStatus());
        Set<Long> ids = RoleConstants.ADMIN.equals(role) ? adminCommunityCacheService.getCommunityIds(id) : Set.of();
        return buildLoginVO(jwtUtil.renewToken(claims), id, user.getUsername(), user.getRealName(), role, ids);
    }

    /** 登出：整个续期会话族拉黑36h（DB 双写 + Redis 缓存） */
    public void logout(String token) {
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null || tokenBlacklistService.isBlacklisted(claims.getId())) {
            // 令牌已过期/非法，无需拉黑，直接视为登出成功（幂等）
            return;
        }
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(jwtUtil.accessTtlSeconds());
        tokenBlacklistService.blacklist(claims.getId(), jwtUtil.extractUserId(claims), expireTime, "LOGOUT");
    }

    private void requireActive(String status) {
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.ACCOUNT_FROZEN);
        }
    }

    private LoginVO buildLoginVO(String token, Long id, String username,
                                 String realName, String role, Set<Long> communityIds) {
        return LoginVO.builder()
                .token(token)
                .expiresIn(jwtUtil.accessTtlSeconds())
                .user(LoginVO.UserSummary.builder()
                        .id(id).username(username).realName(realName)
                        .role(role).boundCommunities(communityIds)
                        .build())
                .build();
    }
}
