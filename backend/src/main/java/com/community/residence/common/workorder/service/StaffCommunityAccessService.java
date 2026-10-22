package com.community.residence.common.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.workorder.entity.StaffCommunity;
import com.community.residence.common.workorder.mapper.StaffCommunityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 人员绑定与调度共用人员行锁；读候选不代替写入时的范围校验。 */
@Service
@RequiredArgsConstructor
public class StaffCommunityAccessService {
    private final SysUserMapper sysUserMapper;
    private final StaffCommunityMapper staffCommunityMapper;

    /** 调用方事务内锁定人员，随后使用当前读校验目标小区，避免与解绑穿透。 */
    public SysUser requireBoundActiveForUpdate(Long staffId, Long communityId) {
        SysUser staff = sysUserMapper.selectByIdForUpdate(staffId);
        if (staff == null || !RoleConstants.STAFF.equals(staff.getRole())) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "服务人员不存在");
        }
        if (!CommonStatus.ACTIVE.equals(staff.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_FROZEN, "服务人员账号已冻结");
        }
        if (staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getStaffId, staffId).eq(StaffCommunity::getCommunityId, communityId)
                .last("FOR UPDATE")).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "服务人员未绑定目标小区");
        }
        return staff;
    }

    /** 只读候选使用相同绑定条件，仍须在写入时重新校验。 */
    public boolean isBound(Long staffId, Long communityId) {
        return communityId != null && staffCommunityMapper.selectCount(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getStaffId, staffId).eq(StaffCommunity::getCommunityId, communityId)) > 0;
    }
}
