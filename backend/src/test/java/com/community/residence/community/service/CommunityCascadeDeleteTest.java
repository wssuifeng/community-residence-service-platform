package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractLambdaWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ResourceNotFoundException;
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
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 社区级联删除测试（R1/R6 v1.1）：级联清单全覆盖、删除顺序（子表先于父表）、
 * 账号体系保留（仅解除绑定）、不存在 404、操作日志留痕。
 * 纯 Mockito 环境，Lambda 条件构造器经 deep-stub wrapper 驱动，实体元数据
 * 需手动注册。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityService 级联删除单元测试")
class CommunityCascadeDeleteTest {

    @Mock
    private CommunityMapper communityMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private HouseStatusHistoryMapper houseStatusHistoryMapper;
    @Mock
    private PublicResourceMapper publicResourceMapper;
    @Mock
    private ResourceTimeslotMapper resourceTimeslotMapper;
    @Mock
    private ResidenceApplicationMapper residenceApplicationMapper;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private LeaseRecordMapper leaseRecordMapper;
    @Mock
    private LeaseReminderMapper leaseReminderMapper;
    @Mock
    private ServiceCategoryMapper serviceCategoryMapper;
    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderAttachmentMapper workOrderAttachmentMapper;
    @Mock
    private WorkOrderProcessMapper workOrderProcessMapper;
    @Mock
    private WorkOrderAssignmentMapper workOrderAssignmentMapper;
    @Mock
    private WorkOrderEvaluationMapper workOrderEvaluationMapper;
    @Mock
    private UnsatisfiedFollowupMapper unsatisfiedFollowupMapper;
    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private NoticeTargetMapper noticeTargetMapper;
    @Mock
    private NoticeViewRecordMapper noticeViewRecordMapper;
    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private FeedbackMessageMapper feedbackMessageMapper;
    @Mock
    private FeedbackAttachmentMapper feedbackAttachmentMapper;
    @Mock
    private ResourceReservationMapper resourceReservationMapper;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper housingTimeslotMapper;
    @Mock
    private ViewingAppointmentMapper viewingAppointmentMapper;
    @Mock
    private StatisticsSnapshotMapper statisticsSnapshotMapper;
    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private SysOperationLogMapper sysOperationLogMapper;

    @InjectMocks
    private CommunityService communityService;

    private Community community;

    /* 级联删除用到的全部实体注册元数据（LambdaWrapper 解析列名依赖） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkOrder.class);
        TableInfoHelper.initTableInfo(assistant, WorkOrderAttachment.class);
        TableInfoHelper.initTableInfo(assistant, WorkOrderProcess.class);
        TableInfoHelper.initTableInfo(assistant, WorkOrderAssignment.class);
        TableInfoHelper.initTableInfo(assistant, WorkOrderEvaluation.class);
        TableInfoHelper.initTableInfo(assistant, UnsatisfiedFollowup.class);
        TableInfoHelper.initTableInfo(assistant, ServiceCategory.class);
        TableInfoHelper.initTableInfo(assistant, Feedback.class);
        TableInfoHelper.initTableInfo(assistant, FeedbackMessage.class);
        TableInfoHelper.initTableInfo(assistant, FeedbackAttachment.class);
        TableInfoHelper.initTableInfo(assistant, NoticeTarget.class);
        TableInfoHelper.initTableInfo(assistant, com.community.residence.notice.entity.NoticeViewRecord.class);
        TableInfoHelper.initTableInfo(assistant, com.community.residence.notice.entity.Notice.class);
        TableInfoHelper.initTableInfo(assistant, ResourceReservation.class);
        TableInfoHelper.initTableInfo(assistant, com.community.residence.reservation.entity.ViolationRecord.class);
        TableInfoHelper.initTableInfo(assistant, Housing.class);
        TableInfoHelper.initTableInfo(assistant, HousingTimeslot.class);
        TableInfoHelper.initTableInfo(assistant, ViewingAppointment.class);
        TableInfoHelper.initTableInfo(assistant, LeaseRecord.class);
        TableInfoHelper.initTableInfo(assistant, LeaseReminder.class);
        TableInfoHelper.initTableInfo(assistant, ResidenceApplication.class);
        TableInfoHelper.initTableInfo(assistant, ResidenceRelation.class);
        TableInfoHelper.initTableInfo(assistant, HouseStatusHistory.class);
        TableInfoHelper.initTableInfo(assistant, ResourceTimeslot.class);
        TableInfoHelper.initTableInfo(assistant, House.class);
        TableInfoHelper.initTableInfo(assistant, Unit.class);
        TableInfoHelper.initTableInfo(assistant, Building.class);
        TableInfoHelper.initTableInfo(assistant, PublicResource.class);
        TableInfoHelper.initTableInfo(assistant, StatisticsSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, Notification.class);
        TableInfoHelper.initTableInfo(assistant, SysAdminCommunity.class);
        TableInfoHelper.initTableInfo(assistant, com.community.residence.auth.entity.SysUser.class);
    }

    @BeforeEach
    void setUp() {
        community = new Community();
        community.setId(9L);
        community.setName("退场社区");
        community.setStatus("ACTIVE");
        /* 404 用例查询的是其他 id，lenient 避免 UnnecessaryStubbing */
        lenient().when(communityMapper.selectById(9L)).thenReturn(community);
    }

    @Test
    @DisplayName("删除不存在/已删社区：404")
    void delete_missingCommunity_throws() {
        when(communityMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> communityService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(communityMapper, times(0)).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("空数据社区删除：仅删本体与绑定、写操作日志，无业务表调用")
    void delete_emptyCommunity_minimalCascade() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);

            communityService.delete(9L);
        }

        /* 业务链按 community_id 查询各执行一次（均空 → 不触发子表删除） */
        verify(workOrderMapper).selectList(any());
        verify(feedbackMapper).selectList(any());
        verify(noticeTargetMapper).selectList(any());
        verify(resourceReservationMapper).selectList(any());
        verify(housingMapper).selectList(any());
        verify(viewingAppointmentMapper).selectList(any());
        verify(leaseRecordMapper).selectList(any());

        /* 无 community_id 空间列的表直接按社区删除（每表一次） */
        verify(serviceCategoryMapper).delete(any());
        verify(residenceApplicationMapper).delete(any());
        verify(residenceRelationMapper).delete(any());
        verify(houseStatusHistoryMapper).delete(any());
        verify(resourceTimeslotMapper).delete(any());
        verify(houseMapper).physicalDeleteByCommunityId(9L);
        verify(unitMapper).physicalDeleteByCommunityId(9L);
        verify(buildingMapper).physicalDeleteByCommunityId(9L);
        verify(publicResourceMapper).physicalDeleteByCommunityId(9L);
        verify(statisticsSnapshotMapper).delete(any());
        verify(notificationMapper).delete(any());
        verify(sysAdminCommunityMapper).delete(any());

        verify(sysOperationLogMapper).insert(any(SysOperationLog.class));
        verify(communityMapper).deleteById(9L);
    }

    @Test
    @DisplayName("有业务数据的社区删除：级联子表→父表全链调用 + 顺序约束 + 操作日志含社区名")
    void delete_withBusinessData_cascadesAll() {
        /* 工单链：1 工单 + 1 评价 */
        WorkOrder order = new WorkOrder();
        order.setId(11L);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(order));
        WorkOrderEvaluation evaluation = new WorkOrderEvaluation();
        evaluation.setId(21L);
        when(workOrderEvaluationMapper.selectList(any())).thenReturn(List.of(evaluation));
        /* 反馈链 */
        Feedback feedback = new Feedback();
        feedback.setId(31L);
        when(feedbackMapper.selectList(any())).thenReturn(List.of(feedback));
        /* 公告链：1 定向目标 */
        NoticeTarget target = new NoticeTarget();
        target.setId(41L);
        target.setNoticeId(42L);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target));
        /* 预约链 */
        ResourceReservation reservation = new ResourceReservation();
        reservation.setId(51L);
        when(resourceReservationMapper.selectList(any())).thenReturn(List.of(reservation));
        /* 房源链 + 看房预约 */
        Housing housing = new Housing();
        housing.setId(61L);
        when(housingMapper.selectList(any())).thenReturn(List.of(housing));
        ViewingAppointment viewing = new ViewingAppointment();
        viewing.setId(71L);
        when(viewingAppointmentMapper.selectList(any())).thenReturn(List.of(viewing));
        /* 租约链 */
        LeaseRecord lease = new LeaseRecord();
        lease.setId(81L);
        when(leaseRecordMapper.selectList(any())).thenReturn(List.of(lease));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);

            communityService.delete(9L);
        }

        /* 工单子表各删一次 */
        verify(workOrderAttachmentMapper).delete(any());
        verify(workOrderProcessMapper).delete(any());
        verify(workOrderAssignmentMapper).delete(any());
        verify(unsatisfiedFollowupMapper).delete(any());
        verify(workOrderEvaluationMapper).deleteBatchIds(List.of(21L));
        verify(workOrderMapper).deleteBatchIds(List.of(11L));
        /* 反馈子表 */
        verify(feedbackMessageMapper).delete(any());
        verify(feedbackAttachmentMapper).delete(any());
        verify(feedbackMapper).deleteBatchIds(List.of(31L));
        /* 公告子表 */
        verify(noticeViewRecordMapper).delete(any());
        verify(noticeTargetMapper).delete(any());
        verify(noticeMapper).deleteBatchIds(List.of(42L));
        /* 预约与违约 */
        verify(violationRecordMapper, times(2)).delete(any());
        verify(resourceReservationMapper).deleteBatchIds(List.of(51L));
        /* 房源链 */
        verify(housingTimeslotMapper).delete(any());
        verify(housingMapper).deleteBatchIds(List.of(61L));
        verify(viewingAppointmentMapper).deleteBatchIds(List.of(71L));
        /* 租约链 */
        verify(leaseReminderMapper).delete(any());
        verify(leaseRecordMapper).deleteBatchIds(List.of(81L));

        /* 删除顺序：子表先于父表（工单链 + 结构链抽查；结构表为物理删除） */
        InOrder order1 = inOrder(workOrderAttachmentMapper, workOrderMapper);
        order1.verify(workOrderAttachmentMapper).delete(any());
        order1.verify(workOrderMapper).deleteBatchIds(List.of(11L));
        InOrder order2 = inOrder(houseMapper, unitMapper, buildingMapper, communityMapper);
        order2.verify(houseMapper).physicalDeleteByCommunityId(9L);
        order2.verify(unitMapper).physicalDeleteByCommunityId(9L);
        order2.verify(buildingMapper).physicalDeleteByCommunityId(9L);
        order2.verify(communityMapper).deleteById(9L);

        /* 操作日志：先写日志后删父表，含社区名可溯源 */
        ArgumentCaptor<SysOperationLog> logCaptor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(sysOperationLogMapper).insert(logCaptor.capture());
        SysOperationLog logged = logCaptor.getValue();
        assertThat(logged.getOperationType()).isEqualTo("DELETE");
        assertThat(logged.getTargetType()).isEqualTo("COMMUNITY");
        assertThat(logged.getTargetId()).isEqualTo(9L);
        assertThat(logged.getContent()).contains("退场社区");
        InOrder order3 = inOrder(sysOperationLogMapper, communityMapper);
        order3.verify(sysOperationLogMapper).insert(any(SysOperationLog.class));
        order3.verify(communityMapper).deleteById(9L);
    }

    @Test
    @DisplayName("账号体系保留：sys_user/resident 无删除调用，仅解除 sys_admin_community 绑定")
    void delete_keepsAccountSystem() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);

            communityService.delete(9L);
        }

        /* sys_user 与 resident 均无删除路径（级联方法中不含对应 mapper 调用，
           绑定解除是唯一的账号侧动作） */
        verify(sysAdminCommunityMapper).delete(any());
        verify(communityMapper).deleteById(9L);
    }
}
