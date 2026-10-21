package com.community.residence.common.workorder.service;

import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.conversation.constant.ConversationStatus;
import com.community.residence.common.conversation.constant.ConversationType;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.workorder.constant.WorkOrderStatus;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.upload.service.FileUploadService;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.common.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.common.workorder.entity.ServiceCategory;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.entity.WorkOrderProcess;
import com.community.residence.common.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.common.workorder.vo.WorkOrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R74 工单完整链路回归（服务侧）：受理 PENDING→TO_ASSIGN + 同一事务建群 +
 * 回写 conversation_id、重复/非法状态受理 5302 且不建群、派单后服务人员入群、
 * 终审关闭前置收窄（仅 COMPLETED）+ 会话资源回收、
 * 通知事件集（提交→社区绑定管理员+超管；受理/接单/提交结果/居民确认/终审关闭→相关管理员）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("工单完整链路（R74，服务侧）")
class WorkOrderR74IntakeTest {

    private static final Long ORDER_ID = 1L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long COMMUNITY_ID = 1L;
    private static final Long ADMIN_USER_ID = 9L;
    private static final Long BOUND_ADMIN_ID = 3L;
    private static final Long SUPER_ADMIN_ID = 2L;
    private static final Long STAFF_ID = 11L;
    private static final Long GROUP_ID = 77L;
    private static final String ORDER_NO = "WO202609220001";

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderProcessMapper processMapper;
    @Mock
    private WorkOrderAssignmentMapper assignmentMapper;
    @Mock
    private WorkOrderAttachmentMapper attachmentMapper;
    @Mock
    private ServiceCategoryMapper categoryMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private StaffCapabilityService staffCapabilityService;
    @Mock
    private StaffScheduleService staffScheduleService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private StaffCommunityAccessService staffCommunityAccessService;

    @InjectMocks
    private WorkOrderService service;

    private WorkOrder order;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "work-order-test"), WorkOrder.class);
        order = new WorkOrder();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setResidentId(RESIDENT_ID);
        order.setCommunityId(COMMUNITY_ID);
        order.setCategoryId(1L);
        order.setTitle("厨房漏水");
        order.setStatus(WorkOrderStatus.PENDING);
        lenient().when(workOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        /* 受理/终审关闭走条件更新（OCR DEF-116）：放行 1 行，走正常流转路径 */
        lenient().when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
        /* 新建工单回填自增主键（通知/时间线需真实工单 ID） */
        lenient().when(workOrderMapper.insert(ArgumentMatchers.<WorkOrder>any())).thenAnswer(invocation -> {
            WorkOrder inserted = invocation.getArgument(0);
            inserted.setId(ORDER_ID);
            return 1;
        });

        Conversation group = new Conversation();
        group.setId(GROUP_ID);
        group.setType(ConversationType.WORK_ORDER_GROUP);
        group.setStatus(ConversationStatus.ACTIVE);
        group.setRelatedId(ORDER_ID);
        lenient().when(conversationService.createWorkOrderGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(group);
        lenient().when(conversationService.findWorkOrderGroup(ORDER_ID)).thenReturn(group);
        lenient().when(sysUserMapper.selectById(STAFF_ID)).thenReturn(staff(STAFF_ID, "STAFF", "服务人员"));
        lenient().when(staffCommunityAccessService.requireBoundActiveForUpdate(anyLong(), anyLong()))
                .thenAnswer(invocation -> sysUserMapper.selectById(invocation.getArgument(0)));
    }

    /** 社区绑定管理员 + 超管：两次 selectList 依次返回（notifySubmit 先社区后超管） */
    private void stubAdminTargets() {
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setCommunityId(COMMUNITY_ID);
        binding.setAdminId(BOUND_ADMIN_ID);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));

        SysUser boundAdmin = staff(BOUND_ADMIN_ID, "ADMIN", "陈国强");
        SysUser superAdmin = staff(SUPER_ADMIN_ID, "SUPER_ADMIN", "超管");
        when(sysUserMapper.selectList(any())).thenReturn(List.of(boundAdmin), List.of(superAdmin));
        lenient().when(sysUserMapper.selectById(STAFF_ID)).thenReturn(staff(STAFF_ID, "STAFF", "服务人员"));
        lenient().when(staffCommunityAccessService.requireBoundActiveForUpdate(anyLong(), anyLong()))
                .thenAnswer(invocation -> sysUserMapper.selectById(invocation.getArgument(0)));
    }

    private SysUser staff(Long id, String role, String realName) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole(role);
        user.setStatus("ACTIVE");
        user.setRealName(realName);
        return user;
    }

    private MockedStatic<SecurityUtils> login(Long userId, boolean isResident) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(isResident);
        return mocked;
    }

    @Test
    @DisplayName("①受理成功：PENDING→TO_ASSIGN + 同一事务建群 + 回写 conversation_id + 时间线 INTAKE + 通知居民与管理员")
    void intake_success() {
        stubAdminTargets();
        try (MockedStatic<SecurityUtils> mocked = login(ADMIN_USER_ID, false)) {
            WorkOrderVO vo = service.intake(ORDER_ID, "已受理，安排师傅");

            assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.TO_ASSIGN);
            assertThat(order.getConversationId()).isEqualTo(GROUP_ID);
            assertThat(vo.getStatus()).isEqualTo(WorkOrderStatus.TO_ASSIGN);
            assertThat(vo.getConversationId()).isEqualTo(GROUP_ID);

            verify(conversationService).createWorkOrderGroup(ORDER_ID, COMMUNITY_ID, RESIDENT_ID,
                    ADMIN_USER_ID, ORDER_NO);
            verify(conversationService).sendSystemMessage(GROUP_ID, "工单已受理，本群用于工单沟通（工单号 " + ORDER_NO + "）");
            /* 受理只建群不回收 */
            verify(conversationService, never()).closeWorkOrderGroup(anyLong());
            verify(processMapper, org.mockito.Mockito.atLeastOnce()).insert(ArgumentMatchers.<WorkOrderProcess>any());
            verify(notificationService).createForResident(eq(RESIDENT_ID), eq(COMMUNITY_ID), anyString(), anyString(),
                    eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
            verify(notificationService).createForUser(eq(BOUND_ADMIN_ID), eq(COMMUNITY_ID), anyString(), anyString(),
                    eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
        }
    }

    @Test
    @DisplayName("②重复受理/非法状态 → 5302，且不建群、不改状态")
    void intake_invalidState_conflict() {
        order.setStatus(WorkOrderStatus.TO_ASSIGN);
        try (MockedStatic<SecurityUtils> mocked = login(ADMIN_USER_ID, false)) {
            assertThatThrownBy(() -> service.intake(ORDER_ID, "再受理一次"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
        }
        verify(conversationService, never()).createWorkOrderGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        assertThat(order.getConversationId()).isNull();
        assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.TO_ASSIGN);
        verify(workOrderMapper, never()).updateById(ArgumentMatchers.<WorkOrder>any());
        /* OCR DEF-116：流转持久化点已改为条件更新——非法受理同样零落库 */
        verify(workOrderMapper, never()).update(ArgumentMatchers.<WorkOrder>any(), any());
    }

    @Test
    @DisplayName("③派单后被派服务人员自动入群（内部接线，不新增端点）")
    void assign_joinsGroup() {
        order.setStatus(WorkOrderStatus.TO_ASSIGN);
        when(sysUserMapper.selectById(STAFF_ID)).thenReturn(staff(STAFF_ID, "STAFF", "李师傅"));
        AssignWorkOrderDTO dto = new AssignWorkOrderDTO();
        dto.setAssigneeId(STAFF_ID);

        try (MockedStatic<SecurityUtils> mocked = login(ADMIN_USER_ID, false)) {
            service.assign(ORDER_ID, dto);
        }

        assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
        verify(conversationService).joinWorkOrderGroup(ORDER_ID, STAFF_ID, "李师傅");
    }

    @Test
    @DisplayName("⑤终审前置收窄：非 COMPLETED（待确认）关闭 → 5302 且不回收会话；COMPLETED → CLOSED + 回收 + 通知")
    void close_requiresCompletedAndRecycles() {
        stubAdminTargets();
        order.setStatus(WorkOrderStatus.TO_CONFIRM);
        try (MockedStatic<SecurityUtils> mocked = login(ADMIN_USER_ID, false)) {
            assertThatThrownBy(() -> service.close(ORDER_ID, "提前关闭"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
        }
        verify(conversationService, never()).closeWorkOrderGroup(anyLong());
        assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.TO_CONFIRM);

        /* 居民确认完成 → 管理员终审关闭：状态 CLOSED + 会话资源回收 + 双向通知 */
        order.setStatus(WorkOrderStatus.COMPLETED);
        try (MockedStatic<SecurityUtils> mocked = login(ADMIN_USER_ID, false)) {
            service.close(ORDER_ID, "终审通过");
        }
        assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.CLOSED);
        verify(conversationService).closeWorkOrderGroup(ORDER_ID);
        verify(conversationService).sendSystemMessage(eq(GROUP_ID), anyString());
        verify(notificationService).createForResident(eq(RESIDENT_ID), eq(COMMUNITY_ID), anyString(), anyString(),
                eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
        verify(notificationService).createForUser(eq(BOUND_ADMIN_ID), eq(COMMUNITY_ID), anyString(), anyString(),
                eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
    }

    @Test
    @DisplayName("⑦提交工单 → 该社区绑定管理员与超管各收到通知（DEF-072），居民不收管理口径")
    void submit_notifiesAdminsAndSuperAdmins() {
        ServiceCategory category = new ServiceCategory();
        category.setId(1L);
        category.setCommunityId(COMMUNITY_ID);
        category.setName("水电维修");
        category.setIsActive(1);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(residenceRelationMapper.selectCount(any())).thenReturn(1L);
        stubAdminTargets();

        CreateWorkOrderDTO dto = new CreateWorkOrderDTO();
        dto.setCategoryId(1L);
        dto.setTitle("厨房漏水");
        dto.setContent("水槽下方渗水");
        dto.setContactPhone("13905710101");

        try (MockedStatic<SecurityUtils> mocked = login(RESIDENT_ID, true)) {
            WorkOrderVO vo = service.create(dto);

            assertThat(vo.getStatus()).isEqualTo(WorkOrderStatus.PENDING);
            /* 提交 → 社区绑定管理员 + 超管（去重后两个 sys_user 身份空间收件人） */
            verify(notificationService).createForUser(eq(BOUND_ADMIN_ID), eq(COMMUNITY_ID), eq("新工单待受理"),
                    anyString(), eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
            verify(notificationService).createForUser(eq(SUPER_ADMIN_ID), eq(COMMUNITY_ID), eq("新工单待受理"),
                    anyString(), eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
            /* 居民提交人本人不收「提交」事件（管理端口径不发居民身份空间） */
            verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(), anyString(),
                    anyString(), anyString(), anyLong());
        }
    }

    @Test
    @DisplayName("DEF-116：等待条件更新后读取赢家状态与会话，不重复副作用")
    void intakeConcurrentWinnerReturnsCurrentGroup() {
        WorkOrder winner = new WorkOrder();
        org.springframework.beans.BeanUtils.copyProperties(order, winner);
        winner.setStatus(WorkOrderStatus.TO_ASSIGN);
        winner.setConversationId(GROUP_ID);
        when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(0);
        when(workOrderMapper.selectOne(any())).thenReturn(winner);
        try (MockedStatic<SecurityUtils> mocked = login(ADMIN_USER_ID, false)) {
            WorkOrderVO vo = service.intake(ORDER_ID, "并发受理");
            assertThat(vo.getStatus()).isEqualTo(WorkOrderStatus.TO_ASSIGN);
            assertThat(vo.getConversationId()).isEqualTo(GROUP_ID);
        }
        verify(processMapper, never()).insert(any(WorkOrderProcess.class));
        verify(conversationService, never()).createWorkOrderGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("DEF-116：等待后已到其他状态仍拒绝，不误当同目标幂等")
    void intakeConcurrentDifferentTargetStillConflicts() {
        WorkOrder winner = new WorkOrder();
        org.springframework.beans.BeanUtils.copyProperties(order, winner);
        winner.setStatus(WorkOrderStatus.ASSIGNED);
        when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(0);
        when(workOrderMapper.selectOne(any())).thenReturn(winner);
        try (MockedStatic<SecurityUtils> mocked = login(ADMIN_USER_ID, false)) {
            assertThatThrownBy(() -> service.intake(ORDER_ID, "并发状态漂移"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
        }
        verify(processMapper, never()).insert(any(WorkOrderProcess.class));
    }
}
