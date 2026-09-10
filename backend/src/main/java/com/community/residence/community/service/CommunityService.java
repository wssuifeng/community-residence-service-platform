package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateCommunityDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.HouseStatusHistory;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.CommunityVO;
import com.community.residence.evaluation.entity.UnsatisfiedFollowup;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.UnsatisfiedFollowupMapper;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.entity.FeedbackAttachment;
import com.community.residence.feedback.entity.FeedbackMessage;
import com.community.residence.feedback.mapper.FeedbackAttachmentMapper;
import com.community.residence.feedback.mapper.FeedbackMapper;
import com.community.residence.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.entity.LeaseReminder;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.mapper.LeaseReminderMapper;
import com.community.residence.messaging.entity.Notification;
import com.community.residence.messaging.mapper.NotificationMapper;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.entity.NoticeViewRecord;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.reservation.entity.ViolationRecord;
import com.community.residence.resident.entity.ResidenceApplication;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.statistics.entity.StatisticsSnapshot;
import com.community.residence.statistics.mapper.StatisticsSnapshotMapper;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.entity.WorkOrderAttachment;
import com.community.residence.workorder.entity.WorkOrderProcess;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.mapper.WorkOrderProcessMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 社区业务逻辑：全局运营主体的增删改查与停用/启用。
 * 社区表是数据级权限锚点（ADMIN 按 id IN 绑定社区过滤，由拦截器处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    private final CommunityMapper communityMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final HouseMapper houseMapper;
    private final HouseStatusHistoryMapper houseStatusHistoryMapper;
    private final PublicResourceMapper publicResourceMapper;
    private final ResourceTimeslotMapper resourceTimeslotMapper;
    private final ResidenceApplicationMapper residenceApplicationMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
    private final LeaseRecordMapper leaseRecordMapper;
    private final LeaseReminderMapper leaseReminderMapper;
    private final ServiceCategoryMapper serviceCategoryMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderAttachmentMapper workOrderAttachmentMapper;
    private final WorkOrderProcessMapper workOrderProcessMapper;
    private final WorkOrderAssignmentMapper workOrderAssignmentMapper;
    private final WorkOrderEvaluationMapper workOrderEvaluationMapper;
    private final UnsatisfiedFollowupMapper unsatisfiedFollowupMapper;
    private final NoticeMapper noticeMapper;
    private final NoticeTargetMapper noticeTargetMapper;
    private final NoticeViewRecordMapper noticeViewRecordMapper;
    private final FeedbackMapper feedbackMapper;
    private final FeedbackMessageMapper feedbackMessageMapper;
    private final FeedbackAttachmentMapper feedbackAttachmentMapper;
    private final ResourceReservationMapper resourceReservationMapper;
    private final ViolationRecordMapper violationRecordMapper;
    private final HousingMapper housingMapper;
    private final HousingTimeslotMapper housingTimeslotMapper;
    private final ViewingAppointmentMapper viewingAppointmentMapper;
    private final StatisticsSnapshotMapper statisticsSnapshotMapper;
    private final NotificationMapper notificationMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final SysOperationLogMapper sysOperationLogMapper;

    /** 创建社区（仅超管，功能级权限在 Controller 声明） */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVO create(CreateCommunityDTO dto) {
        Community community = new Community();
        applyDto(community, dto);
        community.setStatus(CommonStatus.ACTIVE);
        communityMapper.insert(community);
        return CommunityVO.from(community);
    }

    /** 更新社区基本信息（超管全局；社区管理员限绑定社区） */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVO update(Long id, CreateCommunityDTO dto) {
        Community community = requireCommunity(id);
        SecurityUtils.checkCommunityAccess(community.getId());
        applyDto(community, dto);
        communityMapper.updateById(community);
        return CommunityVO.from(community);
    }

    public CommunityVO getById(Long id) {
        return CommunityVO.from(requireCommunity(id));
    }

    /** 社区分页列表（公开；ADMIN 由拦截器自动过滤绑定社区） */
    public PageVO<CommunityVO> page(long page, long size, String status, String keyword) {
        LambdaQueryWrapper<Community> wrapper = new LambdaQueryWrapper<Community>()
                .eq(StringUtils.hasText(status), Community::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Community::getName, keyword)
                        .or()
                        .like(Community::getAddress, keyword))
                .orderByDesc(Community::getId);
        Page<Community> result = communityMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(CommunityVO::from));
    }

    /** 停用/启用社区（仅超管；停用后该社区下所有写业务被 COMMUNITY_INACTIVE 拦截） */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status) {
        Community community = requireCommunity(id);
        community.setStatus(status);
        communityMapper.updateById(community);
        log.info("社区状态变更：communityId={}, status={}, operator={}",
                id, status, SecurityUtils.getUser() != null ? SecurityUtils.getUserId() : "system");
    }

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
    public void delete(Long id) {
        Community community = requireCommunity(id);
        Long operatorId = SecurityUtils.getUserId();

        /* ---- 工单链（V1 #15~18 + #27/28）：附件/处理记录/派单 → 评价/跟进 → 工单 → 服务类别 ---- */
        List<Long> orderIds = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getCommunityId, id))
                .stream().map(WorkOrder::getId).toList();
        if (!orderIds.isEmpty()) {
            workOrderAttachmentMapper.delete(new LambdaQueryWrapper<WorkOrderAttachment>()
                    .in(WorkOrderAttachment::getWorkOrderId, orderIds));
            workOrderProcessMapper.delete(new LambdaQueryWrapper<WorkOrderProcess>()
                    .in(WorkOrderProcess::getWorkOrderId, orderIds));
            workOrderAssignmentMapper.delete(new LambdaQueryWrapper<WorkOrderAssignment>()
                    .in(WorkOrderAssignment::getWorkOrderId, orderIds));
            List<Long> evaluationIds = workOrderEvaluationMapper.selectList(
                    new LambdaQueryWrapper<WorkOrderEvaluation>()
                            .in(WorkOrderEvaluation::getWorkOrderId, orderIds))
                    .stream().map(WorkOrderEvaluation::getId).toList();
            if (!evaluationIds.isEmpty()) {
                unsatisfiedFollowupMapper.delete(new LambdaQueryWrapper<UnsatisfiedFollowup>()
                        .in(UnsatisfiedFollowup::getEvaluationId, evaluationIds));
                workOrderEvaluationMapper.deleteBatchIds(evaluationIds);
            }
            workOrderMapper.deleteBatchIds(orderIds);
        }
        serviceCategoryMapper.delete(new LambdaQueryWrapper<ServiceCategory>()
                .eq(ServiceCategory::getCommunityId, id));

        /* ---- 反馈链（V1 #22~24）：消息/附件 → 反馈单 ---- */
        List<Long> feedbackIds = feedbackMapper.selectList(
                new LambdaQueryWrapper<Feedback>().eq(Feedback::getCommunityId, id))
                .stream().map(Feedback::getId).toList();
        if (!feedbackIds.isEmpty()) {
            feedbackMessageMapper.delete(new LambdaQueryWrapper<FeedbackMessage>()
                    .in(FeedbackMessage::getFeedbackId, feedbackIds));
            feedbackAttachmentMapper.delete(new LambdaQueryWrapper<FeedbackAttachment>()
                    .in(FeedbackAttachment::getFeedbackId, feedbackIds));
            feedbackMapper.deleteBatchIds(feedbackIds);
        }

        /* ---- 公告链（V1 #19~21）：查看记录/目标 → 公告
                （notice 无 community_id，经 notice_target(target_type=COMMUNITY) 定向归属） ---- */
        List<Long> noticeIds = noticeTargetMapper.selectList(
                new LambdaQueryWrapper<NoticeTarget>()
                        .eq(NoticeTarget::getTargetType, "COMMUNITY")
                        .eq(NoticeTarget::getTargetId, id))
                .stream().map(NoticeTarget::getNoticeId).toList();
        if (!noticeIds.isEmpty()) {
            noticeViewRecordMapper.delete(new LambdaQueryWrapper<NoticeViewRecord>()
                    .in(NoticeViewRecord::getNoticeId, noticeIds));
            noticeTargetMapper.delete(new LambdaQueryWrapper<NoticeTarget>()
                    .in(NoticeTarget::getNoticeId, noticeIds));
            noticeMapper.deleteBatchIds(noticeIds);
        }

        /* ---- C7 预约与违约（V1 #25/26）：违约记录按本社区预约/看房预约 related_id 清理 → 预约
                （violation_record 无 FK，RESERVATION_NO_SHOW 关联资源预约、VIEWING_NO_SHOW 关联看房预约） ---- */
        List<Long> reservationIds = resourceReservationMapper.selectList(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(ResourceReservation::getCommunityId, id))
                .stream().map(ResourceReservation::getId).toList();
        if (!reservationIds.isEmpty()) {
            violationRecordMapper.delete(new LambdaQueryWrapper<ViolationRecord>()
                    .in(ViolationRecord::getRelatedId, reservationIds));
            resourceReservationMapper.deleteBatchIds(reservationIds);
        }

        /* ---- C12 房源链（V1 #37~39）：时段/看房预约 → 房源；看房违约记录随之清理 ---- */
        List<Long> housingIds = housingMapper.selectList(
                new LambdaQueryWrapper<Housing>().eq(Housing::getCommunityId, id))
                .stream().map(Housing::getId).toList();
        if (!housingIds.isEmpty()) {
            housingTimeslotMapper.delete(new LambdaQueryWrapper<HousingTimeslot>()
                    .in(HousingTimeslot::getHousingId, housingIds));
            viewingAppointmentMapper.delete(new LambdaQueryWrapper<ViewingAppointment>()
                    .in(ViewingAppointment::getHousingId, housingIds));
            housingMapper.deleteBatchIds(housingIds);
        }
        List<Long> viewingIds = viewingAppointmentMapper.selectList(
                new LambdaQueryWrapper<ViewingAppointment>()
                        .eq(ViewingAppointment::getCommunityId, id))
                .stream().map(ViewingAppointment::getId).toList();
        if (!viewingIds.isEmpty()) {
            violationRecordMapper.delete(new LambdaQueryWrapper<ViolationRecord>()
                    .in(ViolationRecord::getRelatedId, viewingIds));
            viewingAppointmentMapper.deleteBatchIds(viewingIds);
        }

        /* ---- C2/C3 居住与租住（V1 #9/10/12/13）：提醒 → 租约 → 入住申请/居住关系
                （resident 账号不删：跨社区共享账号体系，归属经 residence_relation 表达） ---- */
        List<Long> leaseIds = leaseRecordMapper.selectList(
                new LambdaQueryWrapper<LeaseRecord>().eq(LeaseRecord::getCommunityId, id))
                .stream().map(LeaseRecord::getId).toList();
        if (!leaseIds.isEmpty()) {
            leaseReminderMapper.delete(new LambdaQueryWrapper<LeaseReminder>()
                    .in(LeaseReminder::getLeaseId, leaseIds));
            leaseRecordMapper.deleteBatchIds(leaseIds);
        }
        residenceApplicationMapper.delete(new LambdaQueryWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getCommunityId, id));
        residenceRelationMapper.delete(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getCommunityId, id));

        /* ---- C1 结构（V1 #2~7）：房屋状态历史 → 资源时段 → 房屋 → 单元 → 楼栋 → 资源
                （四张结构表带 @TableLogic 软删除，级联须物理删除以解除 FK 引用，R6 物理删除语义） ---- */
        houseStatusHistoryMapper.delete(new LambdaQueryWrapper<HouseStatusHistory>()
                .eq(HouseStatusHistory::getCommunityId, id));
        resourceTimeslotMapper.delete(new LambdaQueryWrapper<ResourceTimeslot>()
                .eq(ResourceTimeslot::getCommunityId, id));
        houseMapper.physicalDeleteByCommunityId(id);
        unitMapper.physicalDeleteByCommunityId(id);
        buildingMapper.physicalDeleteByCommunityId(id);
        publicResourceMapper.physicalDeleteByCommunityId(id);

        /* ---- 横切与统计（V1 #29/35）：统计快照、通知
                （notification_channel_log 无实体无 FK，数据随通知失效） ---- */
        statisticsSnapshotMapper.delete(new LambdaQueryWrapper<StatisticsSnapshot>()
                .eq(StatisticsSnapshot::getCommunityId, id));
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getCommunityId, id));

        /* ---- 账号绑定解除（V1 #31）：sys_user 保留（跨社区共享体系） ---- */
        sysAdminCommunityMapper.delete(new LambdaQueryWrapper<SysAdminCommunity>()
                .eq(SysAdminCommunity::getCommunityId, id));

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

    /** 按ID取社区，不存在抛 404；供本模块与其他模块校验引用 */
    public Community requireCommunity(Long id) {
        Community community = communityMapper.selectById(id);
        if (community == null) {
            throw new ResourceNotFoundException("社区不存在");
        }
        return community;
    }

    /** 引用校验：社区必须存在且运营中（楼栋/资源等子资源创建时调用） */
    public Community requireActiveCommunity(Long id) {
        Community community = requireCommunity(id);
        if (!CommonStatus.ACTIVE.equals(community.getStatus())) {
            throw new BusinessException(ErrorCode.COMMUNITY_INACTIVE);
        }
        return community;
    }

    private void applyDto(Community community, CreateCommunityDTO dto) {
        community.setName(dto.getName());
        community.setAddress(dto.getAddress());
        community.setContactPhone(dto.getContactPhone());
        community.setContactPerson(dto.getContactPerson());
        community.setDescription(dto.getDescription());
    }
}
