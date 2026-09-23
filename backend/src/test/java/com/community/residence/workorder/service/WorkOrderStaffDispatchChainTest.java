package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 派单链路（⑤）回归：管理端派单 → 服务人员端可见 → 接单 → 处理中 → 提交结果，
 * 以及派单通知按身份空间（DEF-067/075：服务人员 = USER 空间）触达被派单人。
 * 现状后端链路本身是通的，本用例把该链路锁死，防「派单后服务人员接不到单」回潮。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("派单链路：派单 → 服务人员可见 → 接单 → 处理 → 完成（⑤）")
class WorkOrderStaffDispatchChainTest {

    private static final Long ORDER_ID = 3L;
    private static final Long COMMUNITY_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long STAFF_ID = 3L;

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
    private com.community.residence.auth.mapper.SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private com.community.residence.conversation.service.ConversationService conversationService;

    @InjectMocks
    private WorkOrderService workOrderService;

    private WorkOrder order;

    @BeforeAll
    static void initLambdaMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkOrder.class);
    }

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("WO202609220003");
        order.setCommunityId(COMMUNITY_ID);
        order.setResidentId(1L);
        order.setCategoryId(1L);
        order.setTitle("卫生间马桶冲水无力");
        order.setContent("按下冲水键后水流很小");
        order.setPriority("NORMAL");
        order.setStatus(WorkOrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now().minusHours(1));

        when(workOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        /* 接单/处理/提交结果走条件更新（OCR DEF-116）：放行 1 行，走正常流转路径 */
        when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
        SysUser staff = new SysUser();
        staff.setId(STAFF_ID);
        staff.setRole(RoleConstants.STAFF);
        staff.setStatus("ACTIVE");
        staff.setRealName("刘伟民");
        when(sysUserMapper.selectById(STAFF_ID)).thenReturn(staff);
        when(residentMapper.selectById(1L)).thenReturn(null);
    }

    private MockedStatic<SecurityUtils> login(String role, Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.STAFF))
                .thenReturn(RoleConstants.STAFF.equals(role));
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT))
                .thenReturn(RoleConstants.RESIDENT.equals(role));
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN))
                .thenReturn(RoleConstants.ADMIN.equals(role));
        return mocked;
    }

    @Test
    @DisplayName("①管理端派单：写入派单关系（assignee=STAFF_ID）并流转为已派单")
    void adminAssign_writesAssignmentAndStatus() {
        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.ADMIN, ADMIN_ID)) {
            AssignWorkOrderDTO dto = new AssignWorkOrderDTO();
            dto.setAssigneeId(STAFF_ID);
            dto.setRemark("派单给刘伟民");

            workOrderService.assign(ORDER_ID, dto);

            ArgumentCaptor<WorkOrderAssignment> captor = ArgumentCaptor.forClass(WorkOrderAssignment.class);
            verify(assignmentMapper).insert(captor.capture());
            assertThat(captor.getValue().getAssigneeId()).isEqualTo(STAFF_ID);
            assertThat(captor.getValue().getWorkOrderId()).isEqualTo(ORDER_ID);
            assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
            verify(workOrderMapper).updateById(order);
            verify(workOrderMapper, org.mockito.Mockito.never()).update(any(WorkOrder.class), any());
        }
    }

    @Test
    @DisplayName("②服务人员可见：列表按派单关系取本人被派工单（STAFF 分支）")
    void staffList_visibleByAssignment() {
        WorkOrderAssignment assignment = new WorkOrderAssignment();
        assignment.setWorkOrderId(ORDER_ID);
        assignment.setAssigneeId(STAFF_ID);
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkOrder> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(order));
        page.setTotal(1);
        when(workOrderMapper.selectPage(any(), any())).thenReturn(page);

        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.STAFF, STAFF_ID)) {
            var result = workOrderService.page(1, 20, null, null, null, null, null, null, null, null, null);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getId()).isEqualTo(ORDER_ID);
        }
    }

    @Test
    @DisplayName("③④⑤接单 → 处理中 → 提交结果：状态机逐级推进，仅被派单人可操作")
    void staffChain_acceptProcessComplete() {
        WorkOrderAssignment latest = new WorkOrderAssignment();
        latest.setId(7L);
        latest.setWorkOrderId(ORDER_ID);
        latest.setAssigneeId(STAFF_ID);
        when(assignmentMapper.selectOne(any())).thenReturn(latest);

        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.STAFF, STAFF_ID)) {
            order.setStatus(WorkOrderStatus.ASSIGNED);
            workOrderService.accept(ORDER_ID, "已接单");
            assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.ACCEPTED);

            workOrderService.process(ORDER_ID, "已开始处理");
            assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);

            workOrderService.complete(ORDER_ID, "已更换存水弯密封件并试水正常");
            assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.TO_CONFIRM);
        }
        /* 接单/处理/提交结果三次流转均走条件更新持久化（OCR DEF-116） */
        verify(workOrderMapper, org.mockito.Mockito.atLeast(3)).update(eq(order), any());
    }

    @Test
    @DisplayName("③′非被派单人接单：403（越权保护不被链路修复削弱）")
    void nonAssignee_acceptForbidden() {
        WorkOrderAssignment other = new WorkOrderAssignment();
        other.setId(8L);
        other.setWorkOrderId(ORDER_ID);
        other.setAssigneeId(99L);
        when(assignmentMapper.selectOne(any())).thenReturn(other);
        order.setStatus(WorkOrderStatus.ASSIGNED);

        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.STAFF, STAFF_ID)) {
            assertThatThrownBy(() -> workOrderService.accept(ORDER_ID, "抢单"))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("⑥派单通知：被派服务人员按 USER 身份空间触达（createForUser，非居民空间）")
    void assign_notifiesStaffInUserSpace() {
        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.ADMIN, ADMIN_ID)) {
            AssignWorkOrderDTO dto = new AssignWorkOrderDTO();
            dto.setAssigneeId(STAFF_ID);

            workOrderService.assign(ORDER_ID, dto);

            verify(notificationService).createForUser(eq(STAFF_ID), eq(COMMUNITY_ID), eq("新工单派发"),
                    anyString(), eq("WORK_ORDER"), anyString(), eq(ORDER_ID));
        }
    }

    @Test
    @DisplayName("接单通知：居民提交人按 RESIDENT 空间触达（createForResident）")
    void accept_notifiesResidentSubmitter() {
        WorkOrderAssignment latest = new WorkOrderAssignment();
        latest.setId(7L);
        latest.setWorkOrderId(ORDER_ID);
        latest.setAssigneeId(STAFF_ID);
        when(assignmentMapper.selectOne(any())).thenReturn(latest);
        order.setStatus(WorkOrderStatus.ASSIGNED);

        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.STAFF, STAFF_ID)) {
            workOrderService.accept(ORDER_ID, "已接单");
        }
        verify(notificationService).createForResident(eq(1L), eq(COMMUNITY_ID), eq("服务人员已接单"),
                anyString(), eq("WORK_ORDER"), anyString(), anyLong());
    }
}
