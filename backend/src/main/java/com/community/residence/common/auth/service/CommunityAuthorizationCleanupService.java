package com.community.residence.common.auth.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 小区删除时的auth域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityAuthorizationCleanupService {
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeBindings(Long id) {
        /* ---- 账号绑定解除（V1 #31）：sys_user 保留（跨小区共享体系） ---- */
        sysAdminCommunityMapper.delete(new LambdaQueryWrapper<SysAdminCommunity>()
                .eq(SysAdminCommunity::getCommunityId, id));

    }
}
