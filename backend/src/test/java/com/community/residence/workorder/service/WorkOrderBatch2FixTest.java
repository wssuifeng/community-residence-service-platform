package com.community.residence.workorder.service;

import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderProcess;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.vo.WorkOrderVO;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * DEF-001/019/020 修复回归：工单居住关系校验（R9/R12）、
 * 不满意退回端点语义（R22）、驳回通知（R48）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工单第二批修复回归（DEF-001/019/020）")
class WorkOrderBatch2FixTest {

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

    @InjectMocks
    private WorkOrderService service;

    private com.community.residence.workorder.entity.ServiceCategory category;
    private WorkOrder order;

    @BeforeEach
    void setUp() {
        category = new com.community.residence.workorder.entity.ServiceCategory();
        category.setId(1L);
        category.setCommunityId(1L);
        category.setName("维修");
        category.setIsActive(1);

        order = new WorkOrder();
        order.setId(10L);
        order.setOrderNo("WO202609110010");
        order.setResidentId(5L);
        order.setCommunityId(1L);
        order.setCategoryId(1L);
        order.setStatus(WorkOrderStatus.TO_CONFIRM);
        lenient().when(workOrderMapper.selectById(10L)).thenReturn(order);
    }

    /* ---- DEF-001：无居住关系账号提交工单拒绝 ---- */

    @Test
    @DisplayName("无在住关系：提交工单 FORBIDDEN（R9/R12，原 200 落库）")
    void create_noActiveRelation_throws() {
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            whenCategoryLoaded();
            when(residenceRelationMapper.selectCount(any())).thenReturn(0L);

            assertThatThrownBy(() -> service.create(dto()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN))
                    .hasMessageContaining("居住关系");
            verify(workOrderMapper, org.mockito.Mockito.never()).insert(any(WorkOrder.class));
        }
    }

    @Test
    @DisplayName("有在住关系：提交工单放行（R9 入口开放）")
    void create_activeRelation_ok() {
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            whenCategoryLoaded();
            when(residenceRelationMapper.selectCount(any())).thenReturn(1L);
            when(workOrderMapper.insert(any(WorkOrder.class))).thenAnswer(inv -> {
                inv.getArgument(0, WorkOrder.class).setId(10L);
                return 1;
            });
            lenient().when(processMapper.insert(any(WorkOrderProcess.class))).thenReturn(1);

            WorkOrderVO vo = service.create(dto());
            assertThat(vo.getStatus()).isEqualTo(WorkOrderStatus.PENDING);
        }
    }

    /* ---- DEF-019：不满意退回 TO_CONFIRM → IN_PROGRESS ---- */

    @Test
    @DisplayName("退回：TO_CONFIRM → IN_PROGRESS + 时间线 RETURN + 通知服务人员（R22）")
    void returnBack_fromToConfirm_ok() {
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            lenient().when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
            lenient().when(processMapper.insert(any(WorkOrderProcess.class))).thenReturn(1);
            /* notifyCurrentAssignee 走 assignmentMapper.selectOne */
            com.community.residence.workorder.entity.WorkOrderAssignment assignment =
                    new com.community.residence.workorder.entity.WorkOrderAssignment();
            assignment.setAssigneeId(7L);
            lenient().when(assignmentMapper.selectOne(any())).thenReturn(assignment);

            service.returnBack(10L, "未修好");

            assertThat(order.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
            verify(notificationService).create(eq(7L), eq(1L), anyString(), anyString(),
                    anyString(), anyString(), eq(10L));
        }
    }

    @Test
    @DisplayName("退回非 TO_CONFIRM 态（如 COMPLETED）：5302 拒绝")
    void returnBack_fromCompleted_throws() {
        order.setStatus(WorkOrderStatus.COMPLETED);
        try (MockedStatic<SecurityUtils> mocked = mockResident()) {
            assertThatThrownBy(() -> service.returnBack(10L, "再退"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WORK_ORDER_INVALID_TRANSITION));
            verify(processMapper, org.mockito.Mockito.never()).insert(any(WorkOrderProcess.class));
        }
    }

    @Test
    @DisplayName("退回仅限本人（居民）：他人退回 403")
    void returnBack_notOwner_throws() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(999L, "resident9", "RESIDENT", Set.of()));
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);

            assertThatThrownBy(() -> service.returnBack(10L, "退回"))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
        }
    }

    /* ---- DEF-020：驳回通知 ---- */

    @Test
    @DisplayName("驳回：居民收到通知（R48，原零通知）")
    void reject_notifiesResident() {
        order.setStatus(WorkOrderStatus.PENDING);
        try (MockedStatic<SecurityUtils> mocked = mockAdmin()) {
            lenient().when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
            lenient().when(processMapper.insert(any(WorkOrderProcess.class))).thenReturn(1);

            service.reject(10L, "不符合受理范围");

            verify(notificationService).create(eq(5L), eq(1L), eq("工单已驳回"),
                    anyString(), anyString(), anyString(), eq(10L));
        }
    }

    /* ---- 通用脚手架 ---- */

    private void whenCategoryLoaded() {
        when(categoryMapper.selectById(1L)).thenReturn(category);
    }

    private CreateWorkOrderDTO dto() {
        CreateWorkOrderDTO dto = new CreateWorkOrderDTO();
        dto.setCategoryId(1L);
        dto.setTitle("水龙头漏水");
        dto.setContent("厨房水龙头关不紧");
        dto.setContactPhone("13800001111");
        return dto;
    }

    private MockedStatic<SecurityUtils> mockResident() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(5L, "resident1", "RESIDENT", Set.of()));
        mocked.when(SecurityUtils::getUserId).thenReturn(5L);
        mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(true);
        return mocked;
    }

    private MockedStatic<SecurityUtils> mockAdmin() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser).thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
        mocked.when(SecurityUtils::getUserId).thenReturn(9L);
        return mocked;
    }
}
