package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 工单服务补充测试：提交校验、列表角色收敛、时间线装配、取消链路 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderService 补充路径测试")
class WorkOrderServiceExtraTest {

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
    private com.community.residence.resident.mapper.ResidenceRelationMapper residenceRelationMapper;

    @InjectMocks
    private WorkOrderService workOrderService;

    private WorkOrder order;
    private ServiceCategory category;

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo("WO1");
        order.setResidentId(1L);
        order.setCommunityId(1L);
        order.setCategoryId(1L);
        order.setTitle("t");
        order.setStatus(WorkOrderStatus.PENDING);

        category = new ServiceCategory();
        category.setId(1L);
        category.setCommunityId(1L);
        category.setName("Plumbing");
        category.setIsActive(1);
    }

    @Test
    @DisplayName("提交工单：类别停用拒绝")
    void create_inactiveCategory_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            category.setIsActive(0);
            when(categoryMapper.selectById(1L)).thenReturn(category);

            var dto = new com.community.residence.workorder.dto.CreateWorkOrderDTO();
            dto.setCategoryId(1L);
            dto.setTitle("t");
            dto.setContent("c");
            dto.setContactPhone("13800001111");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> workOrderService.create(dto))
                    .isInstanceOf(com.community.residence.common.exception.BusinessException.class)
                    .hasMessageContaining("服务类别不存在或已停用");
        }
    }

    @Test
    @DisplayName("提交工单成功：工单号 WO 前缀 + SUBMIT 时间线留痕")
    void create_success_appendsProcess() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(categoryMapper.selectById(1L)).thenReturn(category);
            /* DEF-001：提交人须在类别归属社区有在住关系 */
            when(residenceRelationMapper.selectCount(any())).thenReturn(1L);
            when(workOrderMapper.insert(any(WorkOrder.class))).thenAnswer(inv -> {
                inv.getArgument(0, WorkOrder.class).setId(1L);
                return 1;
            });
            when(processMapper.insert(any(WorkOrderProcess.class))).thenReturn(1);

            var dto = new com.community.residence.workorder.dto.CreateWorkOrderDTO();
            dto.setCategoryId(1L);
            dto.setTitle("tap");
            dto.setContent("leak");
            dto.setContactPhone("13800001111");

            var vo = workOrderService.create(dto);
            assertThat(vo.getOrderNo()).startsWith("WO");
            assertThat(vo.getStatus()).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("列表：STAFF 仅见派给本人的工单（无派单记录返回空）")
    void page_staffNoAssignment_empty() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.RESIDENT)).thenReturn(false);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.STAFF)).thenReturn(true);
            when(assignmentMapper.selectList(any())).thenReturn(List.of());

            var vo = workOrderService.page(1, 10, null, null, null, null);
            assertThat(vo.getTotal()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("列表：RESIDENT 限本人 + 分页装配（类别/居民姓名）")
    void page_resident_scopedToSelf() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.RESIDENT)).thenReturn(true);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.STAFF)).thenReturn(false);
            when(workOrderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(inv -> {
                        Page<WorkOrder> p = inv.getArgument(0);
                        p.setRecords(List.of(order));
                        p.setTotal(1);
                        return p;
                    });
            when(categoryMapper.selectById(1L)).thenReturn(category);
            Resident r = new Resident();
            r.setRealName("Zhang");
            when(residentMapper.selectById(1L)).thenReturn(r);

            var vo = workOrderService.page(1, 10, null, null, null, null);
            assertThat(vo.getTotal()).isEqualTo(1);
            assertThat(vo.getRecords().get(0).getCategoryName()).isNotNull();
        }
    }

    @Test
    @DisplayName("取消工单：PENDING → CANCELLED（提交人本人）")
    void cancel_pending_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.RESIDENT)).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
            when(processMapper.insert(any(WorkOrderProcess.class))).thenReturn(1);

            workOrderService.cancel(1L, "self solved");
            assertThat(order.getStatus()).isEqualTo("CANCELLED");
        }
    }

    @Test
    @DisplayName("时间线：装配操作人姓名（居民/管理员两类）")
    void timeline_mapsOperatorNames() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(false);
            WorkOrderProcess p1 = new WorkOrderProcess();
            p1.setWorkOrderId(1L);
            p1.setOperatorId(1L);
            p1.setOperatorType("RESIDENT");
            p1.setAction("SUBMIT");
            WorkOrderProcess p2 = new WorkOrderProcess();
            p2.setWorkOrderId(1L);
            p2.setOperatorId(2L);
            p2.setOperatorType("ADMIN");
            p2.setAction("ASSIGN");
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(processMapper.selectList(any())).thenReturn(List.of(p1, p2));
            Resident r = new Resident();
            r.setRealName("Zhang");
            when(residentMapper.selectById(1L)).thenReturn(r);
            SysUser admin = new SysUser();
            admin.setRealName("Admin A");
            when(sysUserMapper.selectById(2L)).thenReturn(admin);

            var timeline = workOrderService.timeline(1L);
            assertThat(timeline).hasSize(2);
            assertThat(timeline.get(0).getOperatorName()).isEqualTo("Zhang");
            assertThat(timeline.get(1).getOperatorName()).isEqualTo("Admin A");
        }
    }
}
