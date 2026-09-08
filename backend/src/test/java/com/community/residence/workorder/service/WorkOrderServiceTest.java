package com.community.residence.workorder.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
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
    private ServiceCategoryMapper categoryMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private NotificationService notificationService;

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
            when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
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
}
