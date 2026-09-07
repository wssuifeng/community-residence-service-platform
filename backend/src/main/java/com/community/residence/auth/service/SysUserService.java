package com.community.residence.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.dto.BindCommunityDTO;
import com.community.residence.auth.dto.ChangePasswordDTO;
import com.community.residence.auth.dto.CreateSysUserDTO;
import com.community.residence.auth.dto.UpdateSysUserDTO;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.auth.vo.BoundCommunityVO;
import com.community.residence.auth.vo.SysUserVO;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Community;
import com.community.residence.community.service.CommunityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 系统用户业务逻辑：账号 CRUD、冻结（吊销令牌）、社区绑定（吊销令牌 + 缓存同步） */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserService {

    private final SysUserMapper sysUserMapper;
    private final SysAdminCommunityMapper adminCommunityMapper;
    private final CommunityService communityService;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;
    private final AdminCommunityCacheService adminCommunityCacheService;

    @Transactional(rollbackFor = Exception.class)
    public SysUserVO create(CreateSysUserDTO dto) {
        checkUsernameUnique(dto.getUsername());
        checkPhoneUnique(dto.getPhone(), null);

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setRole(dto.getRole());
        user.setStatus(CommonStatus.ACTIVE);
        sysUserMapper.insert(user);
        log.info("系统用户已创建：userId={}, role={}, operator={}",
                user.getId(), user.getRole(), SecurityUtils.getUserId());
        return SysUserVO.from(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public SysUserVO update(Long id, UpdateSysUserDTO dto) {
        SysUser user = requireUser(id);
        checkPhoneUnique(dto.getPhone(), id);
        user.setRealName(dto.getRealName());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setRole(dto.getRole());
        sysUserMapper.updateById(user);
        /* 角色变更影响功能级权限，吊销存量令牌强制重新登录 */
        tokenRevocationService.revokeUser(id);
        return SysUserVO.from(user);
    }

    /** 用户详情（ADMIN 角色附绑定社区列表） */
    public SysUserVO getById(Long id) {
        SysUserVO vo = SysUserVO.from(requireUser(id));
        if (RoleConstants.ADMIN.equals(vo.getRole())) {
            vo.setBoundCommunities(listBoundCommunities(id));
        }
        return vo;
    }

    public PageVO<SysUserVO> page(long page, long size, String role, String status, String keyword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(StringUtils.hasText(role), SysUser::getRole, role)
                .eq(StringUtils.hasText(status), SysUser::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getRealName, keyword)
                        .or().like(SysUser::getPhone, keyword))
                .orderByDesc(SysUser::getId);
        Page<SysUser> result = sysUserMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(SysUserVO::from));
    }

    /* 冻结/解冻：不可冻结自己；冻结吊销全部令牌（即时生效） */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status, String reason) {
        if (id.equals(SecurityUtils.getUserId())) {
            throw new BusinessException(ErrorCode.SELF_FREEZE_FORBIDDEN);
        }
        SysUser user = requireUser(id);
        user.setStatus(status);
        sysUserMapper.updateById(user);
        if (CommonStatus.FROZEN.equals(status)) {
            tokenRevocationService.revokeUser(id);
        }
        log.info("系统用户状态变更：userId={}, status={}, reason={}, operator={}",
                id, status, reason, SecurityUtils.getUserId());
    }

    /* 修改密码：校验旧密码；改完吊销令牌强制重新登录 */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordDTO dto) {
        SysUser user = requireUser(SecurityUtils.getUserId());
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_MISMATCH);
        }
        user.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        sysUserMapper.updateById(user);
        tokenRevocationService.revokeUser(user.getId());
    }

    /* 绑定社区：仅 ADMIN 角色可绑定；重复绑定拒绝；变更后吊销令牌并清缓存 */
    @Transactional(rollbackFor = Exception.class)
    public BoundCommunityVO bindCommunity(Long userId, BindCommunityDTO dto) {
        SysUser user = requireUser(userId);
        if (!RoleConstants.ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.BIND_ROLE_INVALID);
        }
        Community community = communityService.requireCommunity(dto.getCommunityId());

        Long exists = adminCommunityMapper.selectCount(new LambdaQueryWrapper<SysAdminCommunity>()
                .eq(SysAdminCommunity::getAdminId, userId)
                .eq(SysAdminCommunity::getCommunityId, dto.getCommunityId()));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "该社区已绑定");
        }

        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setAdminId(userId);
        binding.setCommunityId(dto.getCommunityId());
        adminCommunityMapper.insert(binding);

        adminCommunityCacheService.evict(userId);
        tokenRevocationService.revokeUser(userId);
        log.info("管理员绑定社区：adminId={}, communityId={}, operator={}",
                userId, dto.getCommunityId(), SecurityUtils.getUserId());

        return new BoundCommunityVO(community.getId(), community.getName(),
                community.getAddress(), null);
    }

    /* 解绑社区：变更后吊销令牌并清缓存（数据级权限范围立即收窄） */
    @Transactional(rollbackFor = Exception.class)
    public void unbindCommunity(Long userId, Long communityId) {
        requireUser(userId);
        communityService.requireCommunity(communityId);
        adminCommunityMapper.delete(new LambdaQueryWrapper<SysAdminCommunity>()
                .eq(SysAdminCommunity::getAdminId, userId)
                .eq(SysAdminCommunity::getCommunityId, communityId));
        adminCommunityCacheService.evict(userId);
        tokenRevocationService.revokeUser(userId);
        log.info("管理员解绑社区：adminId={}, communityId={}, operator={}",
                userId, communityId, SecurityUtils.getUserId());
    }

    public List<BoundCommunityVO> listBoundCommunities(Long userId) {
        requireUser(userId);
        List<Long> communityIds = adminCommunityMapper.selectCommunityIdsByAdminId(userId);
        return communityIds.stream()
                .map(id -> {
                    Community c = communityService.requireCommunity(id);
                    return new BoundCommunityVO(c.getId(), c.getName(), c.getAddress(), null);
                })
                .toList();
    }

    public SysUser requireUser(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new ResourceNotFoundException("系统用户不存在");
        }
        return user;
    }

    private void checkUsernameUnique(String username) {
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (count > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "用户名已存在");
        }
    }

    private void checkPhoneUnique(String phone, Long excludeId) {
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, phone)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "手机号已存在");
        }
    }
}
