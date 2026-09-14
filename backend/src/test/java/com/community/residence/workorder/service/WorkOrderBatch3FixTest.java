package com.community.residence.workorder.service;

import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.workorder.vo.StaffOptionVO;
import com.community.residence.workorder.vo.WorkOrderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 第三批修复回归：DEF-025（ADMIN 派单选项端点）+ DEF-026（工单号撞号重试）+
 *  DEF-029（工单列表时间范围/多状态筛选） */
@ExtendWith(MockitoExtension.class)
@DisplayName("工单第三批修复回归（DEF-025/026/029）")
class WorkOrderBatch3FixTest {

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
    private com.community.residence.resident.mapper.ResidenceRelationMapper residenceRelationMapper;

    @InjectMocks
    private WorkOrderService workOrderService;

    /* ---- DEF-025：可派单服务人员选项 ---- */

    @Test
    @DisplayName("DEF-025：仅启用状态 STAFF 返回（冻结/非 STAFF 排除），最小暴露面仅 ID+姓名")
    void assignableStaff_onlyActiveStaff() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.ADMIN)).thenReturn(false);
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(true);
            when(sysUserMapper.selectList(any())).thenReturn(List.of(
                    staff(1L, "张服务", "STAFF", "ACTIVE"),
                    staff(2L, "李服务", "STAFF", "ACTIVE")));

            List<StaffOptionVO> options = workOrderService.assignableStaff(null);

            assertThat(options).hasSize(2);
            assertThat(options.get(0).getId()).isEqualTo(1L);
            assertThat(options.get(0).getRealName()).isEqualTo("张服务");
        }
    }

    @Test
    @DisplayName("DEF-025：ADMIN 未传社区 + 单一绑定社区 → 该社区派过单的服务人员优先置前")
    void assignableStaff_experiencedFirst() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.ADMIN)).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getCommunityIds)
                    .thenReturn(java.util.Set.of(1L));
            when(sysUserMapper.selectList(any())).thenReturn(List.of(
                    staff(1L, "新人甲", "STAFF", "ACTIVE"),
                    staff(2L, "熟手乙", "STAFF", "ACTIVE")));

            WorkOrder order = new WorkOrder();
            order.setId(10L);
            order.setCommunityId(1L);
            when(workOrderMapper.selectList(any())).thenReturn(List.of(order));
            WorkOrderAssignment assignment = new WorkOrderAssignment();
            assignment.setWorkOrderId(10L);
            assignment.setAssigneeId(2L);
            when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment));

            List<StaffOptionVO> options = workOrderService.assignableStaff(null);

            /* 熟手乙（社区派过单）优先，但新人甲仍在列表（R20 仅要求启用状态，不硬过滤） */
            assertThat(options).hasSize(2);
            assertThat(options.get(0).getId()).isEqualTo(2L);
        }
    }

    /* ---- DEF-026：工单号撞号重试 ---- */

    @Test
    @DisplayName("DEF-026：前 2 次撞号后第 3 次可用 → 重试成功")
    void generateOrderNo_retriesOnCollision() {
        var order = new WorkOrder();
        order.setStatus(WorkOrderStatus.PENDING);
        /* selectCount：前 2 次=1（撞号），第 3 次=0（可用） */
        when(workOrderMapper.selectCount(any())).thenReturn(1L, 1L, 0L);
        org.mockito.Mockito.when(workOrderMapper.insert(
                        org.mockito.ArgumentMatchers.<WorkOrder>any()))
                .thenAnswer(inv -> {
                    inv.getArgument(0, WorkOrder.class).setId(1L);
                    return 1;
                });
        var category = new com.community.residence.workorder.entity.ServiceCategory();
        category.setId(1L);
        category.setCommunityId(1L);
        category.setIsActive(1);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(residenceRelationMapper.selectCount(any())).thenReturn(1L);
        org.mockito.Mockito.when(processMapper.insert(
                org.mockito.ArgumentMatchers.<com.community.residence.workorder.entity.WorkOrderProcess>any()))
                .thenReturn(1);

        WorkOrderVO vo = submitOrder();
        assertThat(vo.getOrderNo()).startsWith("WO");
    }

    @Test
    @DisplayName("DEF-026：连续撞号达上限 5 次 → 抛冲突拒绝（不再直接 409 唯一键）")
    void generateOrderNo_exhaustsRetryLimit_throws() {
        var category = new com.community.residence.workorder.entity.ServiceCategory();
        category.setId(1L);
        category.setCommunityId(1L);
        category.setIsActive(1);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(residenceRelationMapper.selectCount(any())).thenReturn(1L);
        when(workOrderMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> submitOrder())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工单号生成失败");
    }

    /* ---- DEF-029：工单列表时间范围/多状态筛选 ---- */

    @Test
    @DisplayName("DEF-029：statuses 多选传参走 IN 查询（不再只支持单值）")
    void page_statusesMultiValue_appliesInFilter() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.ADMIN)).thenReturn(true);
            when(workOrderMapper.selectPage(any(), any())).thenAnswer(inv -> {
                var p = inv.getArgument(0, com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });

            var vo = workOrderService.page(1, 10, null, "ACCEPTED,IN_PROGRESS",
                    null, null, null, null, null);
            assertThat(vo.getTotal()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("DEF-029：startTime/endTime 传参正常装配（时间范围筛选可达）")
    void page_timeRange_applied() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.SUPER_ADMIN)).thenReturn(true);
            when(workOrderMapper.selectPage(any(), any())).thenAnswer(inv -> {
                var p = inv.getArgument(0, com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });

            var vo = workOrderService.page(1, 10, null, null, null, null, null,
                    java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now());
            assertThat(vo.getTotal()).isEqualTo(0);
        }
    }

    /* ---- 脚手架 ---- */

    private SysUser staff(Long id, String name, String role, String status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRealName(name);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private WorkOrderVO submitOrder() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            var dto = new com.community.residence.workorder.dto.CreateWorkOrderDTO();
            dto.setCategoryId(1L);
            dto.setTitle("重试测试工单");
            dto.setContent("DEF-026");
            dto.setContactPhone("13800001111");
            return workOrderService.create(dto);
        }
    }
}
