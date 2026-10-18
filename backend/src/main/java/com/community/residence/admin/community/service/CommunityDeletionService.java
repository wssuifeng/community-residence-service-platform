package com.community.residence.admin.community.service;
import com.community.residence.common.auth.entity.SysOperationLog;
import com.community.residence.common.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.mapper.CommunityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 管理端社区删除协调：单社区单事务、域内清理、保留账号与审计。 */
@Slf4j @Service @RequiredArgsConstructor
public class CommunityDeletionService {
    private final CommunityMapper communityMapper;
    private final SysOperationLogMapper sysOperationLogMapper;
    private final com.community.residence.common.community.service.CommunityService communityService;
    private final com.community.residence.common.conversation.service.CommunityConversationCleanupService communityConversationCleanupService;
    private final com.community.residence.common.lease.service.CommunityLeaseCleanupService communityLeaseCleanupService;
    private final com.community.residence.common.workorder.service.CommunityWorkOrderCleanupService communityWorkOrderCleanupService;
    private final com.community.residence.common.feedback.service.CommunityFeedbackCleanupService communityFeedbackCleanupService;
    private final com.community.residence.common.notice.service.CommunityNoticeCleanupService communityNoticeCleanupService;
    private final com.community.residence.common.reservation.service.CommunityReservationCleanupService communityReservationCleanupService;
    private final com.community.residence.common.housing.service.CommunityHousingCleanupService communityHousingCleanupService;
    private final com.community.residence.common.agreement.service.CommunityAgreementCleanupService communityAgreementCleanupService;
    private final com.community.residence.common.resident.service.CommunityResidentCleanupService communityResidentCleanupService;
    private final com.community.residence.common.community.service.CommunityStructureCleanupService communityStructureCleanupService;
    private final com.community.residence.common.statistics.service.CommunityStatisticsCleanupService communityStatisticsCleanupService;
    private final com.community.residence.common.messaging.service.CommunityNotificationCleanupService communityNotificationCleanupService;
    private final com.community.residence.common.auth.service.CommunityAuthorizationCleanupService communityAuthorizationCleanupService;
    /**
     * 社区级联删除（R1/R6 v1.1，仅超管；50 阶段步骤 1b 前置开发）。
     * 级联清单从 V1 迁移逐表推导：凡 community_id 直接引用的表 + 经结构链
     * （社区→楼栋→单元→房屋）或业务链（公告→notice_target 定向）传递归属的表
     * 全部纳入，删除顺序一律子表在前、父表在后（FK 约束要求）。
     * 账号体系不删：sys_user / resident 均为跨社区共享账号（resident 表无
     * community_id 列，社区归属经 residence_relation 表达），仅随关系删业务数据；
     * sys_admin_community 删除该社区绑定行。不存在/已删返回 404；
     * 删除动作写操作日志（先写后删父表，sys_operation_log 无 FK 不受级联影响）。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "DELETE", targetType = "COMMUNITY", targetId = "#id", content = "'删除社区（级联）'")
    public void delete(Long id) {
        Community community = communityService.requireCommunity(id);
        Long operatorId = SecurityUtils.getUserId();
        communityConversationCleanupService.removeImages(id);
        communityLeaseCleanupService.removeMoveOutApplications(id);
        communityWorkOrderCleanupService.removeOrders(id);
        communityFeedbackCleanupService.removeFeedback(id);
        communityNoticeCleanupService.removeNotices(id);
        communityReservationCleanupService.removeReservations(id);
        communityHousingCleanupService.removeHousing(id);
        communityLeaseCleanupService.removeLeases(id);
        communityWorkOrderCleanupService.removeStaffBindings(id);
        communityAgreementCleanupService.removeTemplates(id);
        communityResidentCleanupService.removeRelations(id);
        communityStructureCleanupService.removeStructures(id);
        communityStatisticsCleanupService.removeStatistics(id);
        communityNotificationCleanupService.removeNotifications(id);
        communityAuthorizationCleanupService.removeBindings(id);
        /* ---- 删除动作写操作日志（sys_operation_log 仅追加、无 FK，先写后删父表） ---- */
        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setOperatorId(operatorId);
        operationLog.setOperatorType("ADMIN");
        operationLog.setCommunityId(id);
        operationLog.setOperationType("DELETE");
        operationLog.setTargetType("COMMUNITY");
        operationLog.setTargetId(id);
        operationLog.setContent("{\"communityName\":\"" + community.getName()
                + "\",\"action\":\"级联删除社区及关联业务数据\"}");
        operationLog.setCreatedAt(LocalDateTime.now());
        sysOperationLogMapper.insert(operationLog);

        /* ---- 父表收尾：community 本体 ---- */
        communityMapper.deleteById(id);
        log.info("社区级联删除完成：communityId={}, name={}, operator={}", id, community.getName(), operatorId);
    }

    /** 删除前关联摘要，仅超管入口；不删除、不猜测历史。 */
    public com.community.residence.common.community.vo.CommunityDeletionImpactVO deletionImpact(Long id) {
        var impact = communityMapper.selectDeletionImpact(id);
        if (impact == null) throw new ResourceNotFoundException("社区不存在");
        impact.setDeletable(true);
        impact.setBlockingReason("");
        String version = impact.getCommunityId() + ":" + impact.getCommunityName() + ":" + impact.getHouses()
                + ":" + impact.getRelations() + ":" + impact.getActiveRelations() + ":" + impact.getLeases()
                + ":" + impact.getActiveLeases() + ":" + impact.getApplications() + ":" + impact.getPendingApplications()
                + ":" + impact.getPayments() + ":" + impact.getRefunds();
        impact.setConfirmationVersion(org.springframework.util.DigestUtils.md5DigestAsHex(version.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return impact;
    }

    /* 重新锁定并核对影响版本：旧客户端/缺失确认/预览后变化均不进入级联操作。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfirmed(Long id, String confirmationVersion, String confirmationName) {
        if (communityMapper.lockForDeletion(id) == null) throw new ResourceNotFoundException("社区不存在");
        communityMapper.lockRelationsForDeletion(id);
        communityMapper.lockLeasesForDeletion(id);
        communityMapper.lockApplicationsForDeletion(id);
        var impact = deletionImpact(id);
        if (!impact.getCommunityName().equals(confirmationName))
            throw new BusinessException(ErrorCode.INVALID_PARAM, "请输入完整社区名称完成最终删除确认");
        if (confirmationVersion == null || !confirmationVersion.equals(impact.getConfirmationVersion()))
            throw new BusinessException(ErrorCode.INVALID_PARAM, "请重新查看删除影响并完成两次确认；关联数据已变化或未确认");
        delete(id);
        SysOperationLog record = new SysOperationLog();
        record.setOperatorId(SecurityUtils.getUserId()); record.setOperatorType("ADMIN");
        record.setCommunityId(id); record.setOperationType("DELETE"); record.setTargetType("COMMUNITY"); record.setTargetId(id);
        record.setContent("{\"action\":\"已确认删除影响\",\"houses\":" + impact.getHouses()
                + ",\"relations\":" + impact.getRelations() + ",\"leases\":" + impact.getLeases()
                + ",\"payments\":" + impact.getPayments() + ",\"refunds\":" + impact.getRefunds()
                + ",\"activeRelations\":" + impact.getActiveRelations() + ",\"activeLeases\":" + impact.getActiveLeases()
                + ",\"applications\":" + impact.getApplications() + ",\"confirmationVersion\":\"" + impact.getConfirmationVersion() + "\"}");
        record.setCreatedAt(LocalDateTime.now()); sysOperationLogMapper.insert(record);
    }

}
