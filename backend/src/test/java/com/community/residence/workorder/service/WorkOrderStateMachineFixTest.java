package com.community.residence.workorder.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-011/012 修复回归：工单状态机完整性。
 * 011：同态/终态重复动作一律 5302 且时间线零新增（R19/R20/R22）；
 * 012：居民取消限 PENDING/TO_ASSIGN，改派限 ASSIGNED（架构 §6.1 权威口径）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工单状态机修复回归（DEF-011/012）")
class WorkOrderStateMachineFixTest {

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
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private ConversationService conversationService;
    @Mock
    private com.community.residence.resident.mapper.ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private StaffCapabilityService staffCapabilityService;
    @Mock
    private StaffScheduleService staffScheduleService;

    @InjectMocks
    private WorkOrderService service;

    private WorkOrder order;

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo("WO202609110001");
        order.setResidentId(5L);
        order.setCommunityId(1L);
        order.setStatus("PENDING");
        /* requireOrder 统一放行（各用例自行设置 order.status） */
        lenient().when(workOrderMapper.selectById(1L)).thenReturn(order);
    }

    @Test
    @DisplayName("驳回处理记录保留真实前置状态：PENDING → REJECTED")
    void reject_processRecordUsesPreTransitionStatus() {
        order.setStatus("PENDING");
        lenient().when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
        lenient().when(processMapper.insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class)))
                .thenReturn(1);
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            service.reject(1L, "不符合受理范围");
        }

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.community.residence.workorder.entity.WorkOrderProcess.class);
        verify(processMapper).insert(captor.capture());
        assertThat(captor.getValue().getOldStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getNewStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("取消处理记录保留真实前置状态：PENDING → CANCELLED")
    void cancel_processRecordUsesPreTransitionStatus() {
        order.setStatus("PENDING");
        lenient().when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
        lenient().when(processMapper.insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class)))
                .thenReturn(1);
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            service.cancel(1L, "计划有变");
        }

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.community.residence.workorder.entity.WorkOrderProcess.class);
        verify(processMapper).insert(captor.capture());
        assertThat(captor.getValue().getOldStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getNewStatus()).isEqualTo("CANCELLED");
    }

    /* ---- DEF-011：同态/终态重复动作 5302 + 时间线零新增 ---- */

    @Test
    @DisplayName("终态重复动作：REJECTED→reject 5302（原 200 + 脏记录）")
    void reject_onRejected_throws() {
        order.setStatus("REJECTED");
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            assertThatThrownBy(() -> service.reject(1L, "再驳回"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
            verify(processMapper, never()).insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class));
        }
    }

    @Test
    @DisplayName("终态重复动作：CANCELLED→cancel 5302（原 200）")
    void cancel_onCancelled_throws() {
        order.setStatus("CANCELLED");
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            assertThatThrownBy(() -> service.cancel(1L, "再取消"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
            verify(processMapper, never()).insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class));
        }
    }

    @Test
    @DisplayName("同态重复：ACCEPTED 态重复 accept 5302（R20 接单后其他人不可再接）")
    void accept_onAccepted_throws() {
        order.setStatus("ACCEPTED");
        /* checkAssignee：最新派单记录指派给 staff1(7L) */
        com.community.residence.workorder.entity.WorkOrderAssignment assignment =
                new com.community.residence.workorder.entity.WorkOrderAssignment();
        assignment.setWorkOrderId(1L);
        assignment.setAssigneeId(7L);
        lenient().when(assignmentMapper.selectOne(any())).thenReturn(assignment);
        try (MockedStatic<SecurityUtils> mocked = mockStaff()) {
            assertThatThrownBy(() -> service.accept(1L, "再接单"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
            verify(processMapper, never()).insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class));
        }
    }

    @Test
    @DisplayName("同态重复：COMPLETED 态重复 confirm 5302（R22 已完成再确认被拒）")
    void confirm_onCompleted_throws() {
        order.setStatus("COMPLETED");
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            assertThatThrownBy(() -> service.confirm(1L, "再确认"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
            verify(processMapper, never()).insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class));
        }
    }

    @Test
    @DisplayName("终态重复：CLOSED→close 幂等成功（需求口径「重复关闭不报错」，原断言 5302 已按新口径更新）")
    void close_onClosed_isIdempotent() {
        order.setStatus("CLOSED");
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            /* R74/接口设计 §9.21.3 复核裁决：重复关闭同一终态属幂等动作（返回成功），
               与 DEF-011「同态重复动作 5302」的其余动作（reject/cancel/accept/confirm）区分；
               非法流转（如 PENDING → CLOSED）仍为 5302，见 WorkOrderCloseIdempotencyTest */
            assertThatCode(() -> service.close(1L, "再关闭")).doesNotThrowAnyException();
            verify(processMapper, never()).insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class));
        }
    }

    /* ---- DEF-012：cancel/assign 合法流转集收紧 ---- */

    @Test
    @DisplayName("取消收紧：ASSIGNED 态居民取消 5302（原 200 放行）")
    void cancel_onAssigned_throws() {
        order.setStatus("ASSIGNED");
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            assertThatThrownBy(() -> service.cancel(1L, "不想要了"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
        }
    }

    @Test
    @DisplayName("取消收紧：ACCEPTED/IN_PROGRESS/TO_CONFIRM/COMPLETED 态取消一律 5302")
    void cancel_afterAcceptance_throws() {
        for (String status : Set.of("ACCEPTED", "IN_PROGRESS", "TO_CONFIRM", "COMPLETED")) {
            order.setStatus(status);
            try (MockedStatic<SecurityUtils> mocked = mockResident()) {
                assertThatThrownBy(() -> service.cancel(1L, "取消"))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                                .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
            }
        }
    }

    @Test
    @DisplayName("取消合法：PENDING/TO_ASSIGN 居民取消放行")
    void cancel_pendingOrToAssign_ok() {
        for (String status : Set.of("PENDING", "TO_ASSIGN")) {
            order.setStatus(status);
            lenient().when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
            lenient().when(processMapper.insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class))).thenReturn(1);
            try (MockedStatic<SecurityUtils> mocked = mockResident()) {
                assertThatCode(() -> service.cancel(1L, "计划有变")).doesNotThrowAnyException();
            }
        }
    }

    @Test
    @DisplayName("改派合法：ASSIGNED 态重新派单放行（覆盖当前处理人）")
    void assign_onAssigned_ok() {
        order.setStatus("ASSIGNED");
        SysUser staff = new SysUser();
        staff.setId(7L);
        staff.setRole("STAFF");
        staff.setStatus("ACTIVE");
        staff.setRealName("张师傅");
        lenient().when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
        lenient().when(assignmentMapper.insert(any(com.community.residence.workorder.entity.WorkOrderAssignment.class))).thenReturn(1);
        lenient().when(processMapper.insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class))).thenReturn(1);
        lenient().when(sysUserMapper.selectById(7L)).thenReturn(staff);
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            var dto = new com.community.residence.workorder.dto.AssignWorkOrderDTO();
            dto.setAssigneeId(7L);
            assertThatCode(() -> service.assign(1L, dto)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("改派收紧：ACCEPTED/COMPLETED/CLOSED 态 assign 一律 5302（原回退放行）")
    void assign_afterAccepted_throws() {
        for (String status : Set.of("ACCEPTED", "IN_PROGRESS", "TO_CONFIRM", "COMPLETED", "CLOSED")) {
            order.setStatus(status);
            SysUser staff = new SysUser();
            staff.setId(7L);
            staff.setRole("STAFF");
            staff.setStatus("ACTIVE");
            lenient().when(sysUserMapper.selectById(7L)).thenReturn(staff);
            try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
                var dto = new com.community.residence.workorder.dto.AssignWorkOrderDTO();
                dto.setAssigneeId(7L);
                assertThatThrownBy(() -> service.assign(1L, dto))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                                .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
            }
        }
    }

    /* ---- OCR DEF-116：transition 条件更新并发防护（受理竞态两分支） ---- */

    @Test
    @DisplayName("OCR DEF-116：并发受理条件更新 0 行 + 重读已到目标状态 → 幂等返回，不重复建群/留痕/通知")
    void intake_concurrentWinner_idempotentNoSideEffects() {
        order.setStatus("PENDING");
        /* 并发赢家已完成 PENDING→TO_ASSIGN：本请求条件更新 0 行，重读为已受理 */
        WorkOrder winner = new WorkOrder();
        winner.setId(1L);
        winner.setOrderNo("WO202609110001");
        winner.setResidentId(5L);
        winner.setCommunityId(1L);
        winner.setStatus("TO_ASSIGN");
        when(workOrderMapper.selectById(1L)).thenReturn(order, winner);
        when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(0);

        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            assertThatCode(() -> service.intake(1L, "并发受理")).doesNotThrowAnyException();
        }
        verify(conversationService, never())
                .createWorkOrderGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(processMapper, never())
                .insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class));
        verify(notificationService, never())
                .createForResident(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("OCR DEF-116：并发受理条件更新 0 行且重读状态漂移（被并发驳回）→ 5302 并发冲突")
    void intake_concurrentDrift_throwsConflict() {
        order.setStatus("PENDING");
        /* 重读为 REJECTED（非目标状态）：漂移按并发冲突拒绝，而非幂等吞掉 */
        WorkOrder drifted = new WorkOrder();
        drifted.setId(1L);
        drifted.setStatus("REJECTED");
        when(workOrderMapper.selectById(1L)).thenReturn(order, drifted);
        when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(0);

        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            assertThatThrownBy(() -> service.intake(1L, "并发受理"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION))
                    .hasMessageContaining("并发");
        }
        verify(conversationService, never())
                .createWorkOrderGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(processMapper, never())
                .insert(any(com.community.residence.workorder.entity.WorkOrderProcess.class));
    }

    /* ---- 通用脚手架（静态工具按角色 mock） ---- */

    private MockedStatic<SecurityUtils> mockResident() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(5L, "resident1", "RESIDENT", Set.of()));
        mocked.when(SecurityUtils::getUserId).thenReturn(5L);
        mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
        /* checkResidentOwner 走 checkAssignee/checkCommunityAccess 链路为 no-op 默认 */
        return mocked;
    }

    private MockedStatic<SecurityUtils> mockStaff() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(7L, "staff1", "STAFF", Set.of()));
        mocked.when(SecurityUtils::getUserId).thenReturn(7L);
        mocked.when(() -> SecurityUtils.hasRole("STAFF")).thenReturn(true);
        return mocked;
    }

    private MockedStatic<SecurityUtils> mockAdmin() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
        mocked.when(SecurityUtils::getUserId).thenReturn(9L);
        return mocked;
    }
}
