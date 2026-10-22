package com.community.residence.common.lease.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.lease.entity.LeaseChangeLog;
import com.community.residence.common.lease.entity.LeaseDepositRefund;
import com.community.residence.common.lease.entity.LeaseMoveOutApplication;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.entity.LeaseReminder;
import com.community.residence.common.lease.mapper.LeaseChangeLogMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.mapper.LeaseReminderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 小区删除时的lease域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityLeaseCleanupService {
    private final LeaseRecordMapper leaseRecordMapper;
    private final LeaseReminderMapper leaseReminderMapper;
    private final LeaseChangeLogMapper leaseChangeLogMapper;
    private final com.community.residence.common.lease.mapper.LeaseMoveOutApplicationMapper leaseMoveOutApplicationMapper;
    private final com.community.residence.common.lease.mapper.LeaseDepositRefundMapper leaseDepositRefundMapper;
    private final com.community.residence.common.agreement.service.CommunityAgreementCleanupService agreementCleanup;
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeMoveOutApplications(Long id) {
        /* ---- 退租申请（V20 lease_move_out_application，R70）：同时带 lease_id / community_id /
                house_id 三个外键，必须早于租约与房屋物理删除（DEF-066 同类硬缺陷，2026-09-21 批次 f 纳入） ---- */
        leaseMoveOutApplicationMapper.delete(new LambdaQueryWrapper<LeaseMoveOutApplication>()
                .eq(LeaseMoveOutApplication::getCommunityId, id));

    }
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeLeases(Long id) {
        /* ---- C2/C3 居住与租住（V1 #9/10/12/13 + V18 协议/变更历史 + V22 押金退还）：提醒 →
                协议/变更历史/押金退还记录 → 租约 → 入住申请/居住关系
                （resident 账号不删：跨小区共享账号体系，归属经 residence_relation 表达） ---- */
        List<Long> leaseIds = leaseRecordMapper.selectList(
                new LambdaQueryWrapper<LeaseRecord>().eq(LeaseRecord::getCommunityId, id))
                .stream().map(LeaseRecord::getId).toList();
        if (!leaseIds.isEmpty()) {
            leaseReminderMapper.delete(new LambdaQueryWrapper<LeaseReminder>()
                    .in(LeaseReminder::getLeaseId, leaseIds));
            /* V18 两表 + V22 押金退还记录外键指向租约，必须早于租约物理删除 */
            agreementCleanup.removeForLeases(leaseIds);
            leaseChangeLogMapper.delete(new LambdaQueryWrapper<LeaseChangeLog>()
                    .in(LeaseChangeLog::getLeaseId, leaseIds));
            leaseDepositRefundMapper.delete(new LambdaQueryWrapper<LeaseDepositRefund>()
                    .in(LeaseDepositRefund::getLeaseId, leaseIds));
            leaseRecordMapper.deleteBatchIds(leaseIds);
        }
    }
}
