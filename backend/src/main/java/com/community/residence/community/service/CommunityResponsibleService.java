package com.community.residence.community.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.vo.ResponsibleAdminVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 责任人只保存账号引用；联系人展示从账号批量读取。 */
@Service
@RequiredArgsConstructor
public class CommunityResponsibleService {

    private final SysUserMapper sysUserMapper;
    private final CommunityMapper communityMapper;
    private final com.community.residence.auth.mapper.SysAdminCommunityMapper bindingMapper;
    private final com.community.residence.auth.service.AdminCommunityCacheService adminCommunityCacheService;
    private final com.community.residence.auth.service.TokenRevocationService tokenRevocationService;

    /* 社区保存已获得本社区行锁；只维护责任人来源，保留超管手工来源。变更有效权限才吊销令牌。 */
    public void synchronizeBinding(Long communityId, Long responsibleId) {
        var bindings = bindingMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.community.residence.auth.entity.SysAdminCommunity>()
                .eq(com.community.residence.auth.entity.SysAdminCommunity::getCommunityId, communityId));
        com.community.residence.auth.entity.SysAdminCommunity target = null;
        for (var binding : bindings) {
            if (Objects.equals(binding.getAdminId(), responsibleId)) { target = binding; continue; }
            if (!Integer.valueOf(1).equals(binding.getResponsibleGranted())) continue;
            if (Integer.valueOf(1).equals(binding.getManualGranted())) {
                binding.setResponsibleGranted(0);
                bindingMapper.updateById(binding);
            } else {
                bindingMapper.deleteById(binding.getId());
                invalidate(binding.getAdminId());
            }
        }
        if (responsibleId == null) return;
        if (target == null) {
            target = new com.community.residence.auth.entity.SysAdminCommunity();
            target.setAdminId(responsibleId); target.setCommunityId(communityId);
            target.setManualGranted(0); target.setResponsibleGranted(1);
            bindingMapper.insert(target);
            invalidate(responsibleId);
        } else if (!Integer.valueOf(1).equals(target.getResponsibleGranted())) {
            target.setResponsibleGranted(1);
            bindingMapper.updateById(target);
        }
    }

    private void invalidate(Long adminId) {
        tokenRevocationService.revokeAdminUser(adminId);
        adminCommunityCacheService.evict(adminId);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { adminCommunityCacheService.evict(adminId); }
            });
        }
    }

    public void validate(Long id) {
        if (id == null) {
            return;
        }
        SysUser user = sysUserMapper.selectByIdForUpdate(id);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "社区责任人必须是系统内的ADMIN管理员账号");
        }
    }

    /* 已绑定的责任人必须保持ADMIN；先更换或解除引用，再改账号角色。 */
    public void validateRoleChange(Long id, String role) {
        if ("ADMIN".equals(role)) {
            return;
        }
        sysUserMapper.selectByIdForUpdate(id);
        if (communityMapper.selectResponsibleReferenceForUpdate(id) != null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "该账号仍是社区责任人，请先更换或解除责任人再修改角色");
        }
    }

    public void fillContacts(List<Community> communities) {
        List<Long> ids = communities.stream().map(Community::getResponsibleAdminId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, SysUser> users = ids.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(ids).stream().filter(user -> "ADMIN".equals(user.getRole()))
                .collect(Collectors.toMap(SysUser::getId, user -> user));
        for (Community community : communities) {
            SysUser user = community.getResponsibleAdminId() == null ? null : users.get(community.getResponsibleAdminId());
            community.setContactPerson(user == null ? null : user.getRealName());
            community.setContactPhone(user == null ? null : user.getPhone());
        }
    }

    public PageVO<ResponsibleAdminVO> candidates(Long communityId, long page, long size, String keyword) {
        if (!SecurityUtils.hasRole("ADMIN") && !SecurityUtils.hasRole("SUPER_ADMIN")) {
            throw new ForbiddenException("仅管理员可选择社区责任人");
        }
        if (SecurityUtils.hasRole("ADMIN") && communityId == null) {
            throw new ForbiddenException("请选择可管理的社区");
        }
        if (communityId != null) {
            SecurityUtils.checkCommunityAccess(communityId);
        }
        return PageVO.of(sysUserMapper.selectResponsibleCandidates(
                new Page<>(Math.max(1, page), Math.max(1, Math.min(100, size))), communityId, keyword));
    }
}
