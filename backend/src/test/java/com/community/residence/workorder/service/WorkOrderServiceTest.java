package com.community.residence.workorder.service;

import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.mapper.WorkOrderProcessMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 工单状态机测试：全链路合法流转 + 非法流转拒绝 + STAFF 越权拦截 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderService 单元测试")
class WorkOrderServiceTest {

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
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private ConversationService conversationService;

    @Mock
    private StaffScheduleService staffScheduleService;

    @InjectMocks
    private WorkOrderService workOrderService;

    private WorkOrder order;

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo("WO202609080001");
        order.setResidentId(1L);
        order.setCommunityId(1L);
        order.setCategoryId(1L);
        order.setStatus(WorkOrderStatus.PENDING);
    }

    private void loginAs(String role, Long userId) {
        // 预留：跨角色场景测试辅助
    }

    private AssignWorkOrderDTO assignDto(Long staffId) {
        AssignWorkOrderDTO dto = new AssignWorkOrderDTO();
        dto.setAssigneeId(staffId);
        return dto;
    }

    @Test
    @DisplayName("状态机全链路：PENDING→TO_ASSIGN→ASSIGNED→ACCEPTED→IN_PROGRESS→TO_CONFIRM→COMPLETED→CLOSED")
    void fullLifecycle_legalTransitions() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(false);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            /* assign 持久化走 updateById；其余流转走条件更新（OCR DEF-116）——两路各留一桩 */
            when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
            when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
            when(processMapper.insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class))).thenReturn(1);

            // 派单（含 STAFF 校验）：PENDING → ASSIGNED
            com.community.residence.auth.entity.SysUser staff = new com.community.residence.auth.entity.SysUser();
            staff.setId(9L);
            staff.setRole("STAFF");
            staff.setStatus("ACTIVE");
            staff.setRealName("Wang");
            when(sysUserMapper.selectById(9L)).thenReturn(staff);
            workOrderService.assign(1L, assignDto(9L));
            assertThat(order.getStatus()).isEqualTo("ASSIGNED");

            // STAFF 操作链路的派单关系桩（当前处理人 = 9 号）
            WorkOrderAssignment assignment = new WorkOrderAssignment();
            assignment.setId(1L);
            assignment.setWorkOrderId(1L);
            assignment.setAssigneeId(9L);
            when(assignmentMapper.selectOne(any())).thenReturn(assignment);
            when(assignmentMapper.updateById(any(WorkOrderAssignment.class))).thenReturn(1);

            // 逐级流转验证（STAFF 段以处理人身份操作）
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(9L);
            order.setStatus(WorkOrderStatus.ASSIGNED);
            workOrderService.accept(1L, "got it");
            assertThat(order.getStatus()).isEqualTo("ACCEPTED");

            order.setStatus(WorkOrderStatus.ACCEPTED);
            workOrderService.process(1L, "on site");
            assertThat(order.getStatus()).isEqualTo("IN_PROGRESS");

            order.setStatus(WorkOrderStatus.IN_PROGRESS);
            workOrderService.complete(1L, "fixed");
            assertThat(order.getStatus()).isEqualTo("TO_CONFIRM");

            order.setStatus(WorkOrderStatus.TO_CONFIRM);
            // 居民本人确认（提交人 = 1 号）
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            workOrderService.confirm(1L, "ok");
            assertThat(order.getStatus()).isEqualTo("COMPLETED");

            // 管理员关闭（终态）
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            workOrderService.close(1L, "done");
            assertThat(order.getStatus()).isEqualTo("CLOSED");
        }
    }

    @Test
    @DisplayName("非法流转：PENDING 直接 CONFIRM 拒绝（WORK_ORDER_INVALID_TRANSITION 5302）")
    void confirm_fromPending_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            // 居民本人确认
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(workOrderMapper.selectById(1L)).thenReturn(order);

            assertThatThrownBy(() -> workOrderService.confirm(1L, "ok"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
        }
    }

    @Test
    @DisplayName("非法流转：COMPLETED 后不可 CANCELLED（终态保护）")
    void cancel_fromCompleted_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            order.setStatus(WorkOrderStatus.COMPLETED);
            when(workOrderMapper.selectById(1L)).thenReturn(order);

            assertThatThrownBy(() -> workOrderService.cancel(1L, "late cancel"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CANCELLED");
        }
    }

    @Test
    @DisplayName("STAFF 越权：非被派单人接单拒绝（403 ForbiddenException）")
    void accept_notAssignee_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(999L);
            order.setStatus(WorkOrderStatus.ASSIGNED);
            when(workOrderMapper.selectById(1L)).thenReturn(order);

            WorkOrderAssignment assignment = new WorkOrderAssignment();
            assignment.setId(1L);
            assignment.setWorkOrderId(1L);
            assignment.setAssigneeId(9L);  // 派给 9 号
            when(assignmentMapper.selectOne(any())).thenReturn(assignment);

            assertThatThrownBy(() -> workOrderService.accept(1L, "hi"))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class)
                    .hasMessageContaining("被派单");
        }
    }

    @Test
    @DisplayName("DEF-A：确认完成/关闭工单缺省备注（null）不抛异常，状态流转仍正确")
    void confirmAndClose_withNullRemark_succeeds() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(true);
            /* 居民本人确认：工单提交人 = 1 号（DEF-A 前端不传 body，备注为 null） */
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            order.setStatus(WorkOrderStatus.TO_CONFIRM);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            /* 流转走条件更新（OCR DEF-116）：放行 1 行，走正常路径而非并发幂等分支 */
            when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);

            workOrderService.confirm(1L, null);
            assertThat(order.getStatus()).isEqualTo("COMPLETED");

            /* 管理员关闭：已完成 → 已关闭，同样缺省备注 */
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(false);
            workOrderService.close(1L, null);
            assertThat(order.getStatus()).isEqualTo("CLOSED");
        }
    }

    @Test
    @DisplayName("DEF-A 权限不变：非工单提交人的居民确认完成被拒（403 ForbiddenException）")
    void confirm_notOwner_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole("RESIDENT")).thenReturn(true);
            /* 当前居民 = 2 号，工单提交人 = 1 号 */
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            order.setStatus(WorkOrderStatus.TO_CONFIRM);
            when(workOrderMapper.selectById(1L)).thenReturn(order);

            assertThatThrownBy(() -> workOrderService.confirm(1L, null))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class)
                    .hasMessageContaining("仅工单提交人");
        }
    }

    @Test
    @DisplayName("DEF-A 权限不变：关闭工单仍先做社区数据权限校验（跨界管理员被拒）")
    void close_otherCommunityAdmin_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            order.setStatus(WorkOrderStatus.COMPLETED);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils.checkCommunityAccess(1L))
                    .thenThrow(new com.community.residence.common.exception.ForbiddenException("无权操作其他社区数据"));

            assertThatThrownBy(() -> workOrderService.close(1L, null))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class)
                    .hasMessageContaining("无权操作其他社区数据");
        }
    }

    @Test
    @DisplayName("派单：目标必须是 STAFF 角色（非 STAFF 拒绝）")
    void assign_nonStaff_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            com.community.residence.auth.entity.SysUser admin = new com.community.residence.auth.entity.SysUser();
            admin.setId(2L);
            admin.setRole("ADMIN");
            admin.setStatus("ACTIVE");
            when(sysUserMapper.selectById(2L)).thenReturn(admin);

            assertThatThrownBy(() -> workOrderService.assign(1L, assignDto(2L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("服务人员不存在");
        }
    }

    @Test
    void directAssignmentCreatesCommunicationGroupBeforeJoiningStaff() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(2L);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            SysUser staff = new SysUser(); staff.setId(9L); staff.setRole("STAFF"); staff.setStatus("ACTIVE");
            when(sysUserMapper.selectById(9L)).thenReturn(staff);
            workOrderService.assign(1L, assignDto(9L));
            var calls = org.mockito.Mockito.inOrder(conversationService);
            calls.verify(conversationService).createWorkOrderGroup(1L, 1L, 1L, 2L, order.getOrderNo());
            calls.verify(conversationService).joinWorkOrderGroup(1L, 9L, staff.getRealName());
        }
    }

    @Test void restorePendingGroupIsRejectedWithoutChangingStatus() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            assertThatThrownBy(() -> workOrderService.ensureConversation(1L)).hasMessageContaining("请先受理");
            org.mockito.Mockito.verifyNoInteractions(conversationService);
            assertThat(order.getStatus()).isEqualTo("PENDING");
        }
    }
    @Test void residentCannotRestoreOrJoinAsAdministrator() {
        try (var security = mockStatic(SecurityUtils.class)) {
            assertThatThrownBy(() -> workOrderService.ensureConversation(1L)).isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
            org.mockito.Mockito.verifyNoInteractions(conversationService);
        }
    }

    @Test
    void restoreAssignedGroupOnlyWritesReferenceAndAddsCurrentAssignee() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            security.when(SecurityUtils::getUserId).thenReturn(2L);
            order.setStatus(WorkOrderStatus.ASSIGNED);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            var group = new com.community.residence.conversation.entity.Conversation();
            group.setId(33L); group.setStatus("ACTIVE");
            when(conversationService.createWorkOrderGroup(1L, 1L, 1L, 2L, order.getOrderNo())).thenReturn(group);
            var assignment = new WorkOrderAssignment(); assignment.setAssigneeId(9L);
            when(assignmentMapper.selectOne(any())).thenReturn(assignment);

            var result = workOrderService.ensureConversation(1L);

            assertThat(result.getConversationId()).isEqualTo(33L);
            assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
            org.mockito.Mockito.verify(workOrderMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
            org.mockito.Mockito.verify(workOrderMapper, org.mockito.Mockito.never()).updateById(any(WorkOrder.class));
            org.mockito.Mockito.verify(conversationService).joinWorkOrderGroup(1L, 9L, null);
            org.mockito.Mockito.verify(processMapper, org.mockito.Mockito.never()).insert(
                    org.mockito.ArgumentMatchers.any(com.community.residence.workorder.entity.WorkOrderProcess.class));
            org.mockito.Mockito.verifyNoInteractions(notificationService);
        }
    }

    @Test
    void restoreClosedOrderKeepsClosedGroupReadOnly() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);
            security.when(SecurityUtils::getUserId).thenReturn(2L);
            order.setStatus(WorkOrderStatus.CLOSED); order.setConversationId(33L);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            var group = new com.community.residence.conversation.entity.Conversation();
            group.setId(33L); group.setStatus("CLOSED");
            when(conversationService.createWorkOrderGroup(1L, 1L, 1L, 2L, order.getOrderNo())).thenReturn(group);

            assertThat(workOrderService.ensureConversation(1L).getStatus()).isEqualTo(WorkOrderStatus.CLOSED);
            org.mockito.Mockito.verify(conversationService).closeWorkOrderGroup(1L);
            org.mockito.Mockito.verify(conversationService, org.mockito.Mockito.never()).joinWorkOrderGroup(any(), any(), any());
            org.mockito.Mockito.verify(workOrderMapper, org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.<WorkOrder>any(), any());
        }
    }

    @Test
    void restoreGroupOutsideManagedCommunityIsForbidden() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            security.when(() -> SecurityUtils.checkCommunityAccess(1L)).thenThrow(
                    new com.community.residence.common.exception.ForbiddenException("无权操作未绑定社区的数据"));
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            assertThatThrownBy(() -> workOrderService.ensureConversation(1L))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
            org.mockito.Mockito.verifyNoInteractions(conversationService, assignmentMapper);
        }
    }

}
