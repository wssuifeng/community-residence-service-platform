package com.community.residence.workorder.service;

import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderProcess;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 终审关闭幂等回归：需求口径「重复关闭不报错」——
 * 已 `CLOSED` 的工单再次关闭**直接成功返回**（不重复写处理记录、不重复通知、不重复关群）；
 * 而**非法流转**（如 `PENDING → CLOSED`）仍按状态机返回 5302，不被幂等分支吞掉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工单终审关闭幂等回归")
class WorkOrderCloseIdempotencyTest {

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
    private ConversationService conversationService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileUploadService fileUploadService;

    @InjectMocks
    private WorkOrderService service;

    private WorkOrder order;

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo("WO202609220001");
        order.setResidentId(5L);
        order.setCommunityId(1L);
        lenient().when(workOrderMapper.selectById(1L)).thenReturn(order);
    }

    private MockedStatic<SecurityUtils> mockAdmin() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        UserContext admin = new UserContext();
        admin.setUserId(9L);
        admin.setRole("ADMIN");
        admin.setCommunityIds(Set.of(1L));
        mocked.when(SecurityUtils::getUser).thenReturn(admin);
        mocked.when(SecurityUtils::getUserId).thenReturn(9L);
        return mocked;
    }

    @Test
    @DisplayName("已 CLOSED 的工单重复关闭：幂等成功，不新增处理记录、不重复通知/关群")
    void close_onClosed_isIdempotent() {
        order.setStatus("CLOSED");
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            assertThatCode(() -> service.close(1L, "再次关闭")).doesNotThrowAnyException();
        }
        verify(processMapper, never()).insert(any(WorkOrderProcess.class));
        verify(notificationService, never()).createForResident(any(), any(), any(), any(), any(), any(), any());
        verify(conversationService, never()).closeWorkOrderGroup(any());
    }

    @Test
    @DisplayName("非法流转 PENDING → CLOSED：仍 5302（幂等分支不吞非法流转）")
    void close_onPending_stillRejected() {
        order.setStatus("PENDING");
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            assertThatThrownBy(() -> service.close(1L, "越级关闭"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
        }
        verify(processMapper, never()).insert(any(WorkOrderProcess.class));
    }

    @Test
    @DisplayName("合法路径 COMPLETED → CLOSED：正常关闭并留痕（幂等分支不误伤首次关闭）")
    void close_onCompleted_succeeds() {
        order.setStatus("COMPLETED");
        lenient().when(processMapper.insert(any(WorkOrderProcess.class))).thenReturn(1);
        /* 终审关闭走条件更新（OCR DEF-116）：放行 1 行，走正常关闭路径 */
        lenient().when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            assertThatCode(() -> service.close(1L, "终审通过")).doesNotThrowAnyException();
        }
        assertThat(order.getStatus()).isEqualTo("CLOSED");
        verify(processMapper).insert(any(WorkOrderProcess.class));
    }
}
