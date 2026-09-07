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

    /** 管理端登录：SUPER_ADMIN/ADMIN/STAFF（sys_user 表），ADMIN 加载绑定社区 */
    public LoginVO adminLogin(LoginDTO dto) {
        SysUser user = sysUserMapper.selectByUsername(dto.getUsername());
        /* 登录失败统一措辞（不区分账号不存在/密码错误，防账号枚举）；账号存在但被冻结时明确提示 */
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        requireActive(user.getStatus());

        Set<Long> communityIds = RoleConstants.ADMIN.equals(user.getRole())
                ? adminCommunityCacheService.getCommunityIds(user.getId())
                : Set.of();

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return buildLoginVO(token, user.getId(), user.getUsername(), user.getRealName(), user.getRole(), communityIds);
    }

    /** 居民端登录（resident 表），登录即视为 RESIDENT 角色 */
    public LoginVO residentLogin(LoginDTO dto) {
        Resident resident = residentMapper.selectByUsername(dto.getUsername());
        if (resident == null || !passwordEncoder.matches(dto.getPassword(), resident.getPasswordHash())) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        requireActive(resident.getStatus());

        String token = jwtUtil.generateToken(resident.getId(), resident.getUsername(), RoleConstants.RESIDENT);
        return buildLoginVO(token, resident.getId(), resident.getUsername(),
                resident.getRealName(), RoleConstants.RESIDENT, Set.of());
    }

    /** 登出：当前令牌拉入黑名单（DB 双写 + Redis 缓存） */
    public void logout(String token) {
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            // 令牌已过期/非法，无需拉黑，直接视为登出成功（幂等）
            return;
        }
        LocalDateTime expireTime = LocalDateTime.ofInstant(
                jwtUtil.extractExpiration(claims).toInstant(), ZoneId.systemDefault());
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
                .expiresIn(7200)
                .user(LoginVO.UserSummary.builder()
                        .id(id).username(username).realName(realName)
                        .role(role).boundCommunities(communityIds)
                        .build())
                .build();
    }
}
