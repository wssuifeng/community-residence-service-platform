package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.DispatchFlag;
import com.community.residence.common.constant.WorkOrderPriority;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.result.PageVO;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.entity.WorkOrderProcess;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.workorder.vo.StaffOptionVO;
import com.community.residence.workorder.vo.WorkOrderVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工单派单调度增强测试（V19）：候选人推荐档位与排序、调度标记与等待时长、
    处理人在手负载、列表排序（DEFAULT 回归锁定 + WAIT_DESC/PRIORITY） */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderService 派单调度增强（V19）")
class WorkOrderDispatchTest {

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
    @Mock
    private StaffCapabilityService staffCapabilityService;
    @Mock
    private StaffScheduleService staffScheduleService;

    @InjectMocks
    private WorkOrderService workOrderService;

    /** Lambda 列名 → 数据库列名解析依赖 TableInfo 缓存（纯单测无 Spring 上下文，需显式初始化） */
    @BeforeAll
    static void initLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);
    }

    /* ---- 派单候选：推荐档位与排序 ---- */

    @Test
    @DisplayName("派单候选：四档推荐（常驻+擅长 > 常驻 > 擅长 > 其他）并按档位升序返回")
    void assignableStaff_recommendLevelTiers() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectList(any())).thenReturn(List.of(
                    staff(1L, "常驻且擅长"), staff(2L, "仅常驻"),
                    staff(3L, "仅擅长"), staff(4L, "其他")));
            when(staffCapabilityService.communityIdsByStaff(any()))
                    .thenReturn(Map.of(1L, List.of(1L), 2L, List.of(1L)));
            when(staffCapabilityService.categoryIdsByStaff(any()))
                    .thenReturn(Map.of(1L, List.of(5L), 3L, List.of(5L)));
            when(staffCapabilityService.communityNamesByStaff(any()))
                    .thenReturn(Map.of(1L, "幸福社区", 2L, "幸福社区"));
            /* 仅 3 号今日排了早班，1 号未排班 → todayShiftLabel 为空 */
            when(staffScheduleService.shiftLabelsOn(any(), any(), any()))
                    .thenReturn(Map.of(3L, "早班"));

            List<StaffOptionVO> options = workOrderService.assignableStaff(1L, 5L);

            assertThat(options).extracting(StaffOptionVO::getId).containsExactly(1L, 2L, 3L, 4L);
            assertThat(options).extracting(StaffOptionVO::getRecommendLevel)
                    .containsExactly(1, 2, 3, 4);
            assertThat(options).extracting(StaffOptionVO::getMatchedCommunity)
                    .containsExactly(true, true, false, false);
            assertThat(options).extracting(StaffOptionVO::getMatchedCategory)
                    .containsExactly(true, false, true, false);
            assertThat(options.get(0).getCommunityNames()).isEqualTo("幸福社区");
            assertThat(options.get(0).getTodayShiftLabel()).isNull();
            assertThat(options.get(2).getTodayShiftLabel()).isEqualTo("早班");
            assertThat(options).allSatisfy(vo -> assertThat(vo.getActiveOrderCount()).isEqualTo(0));
        }
    }

    @Test
    @DisplayName("派单候选：同档位按在手工单数升序（负载均衡），在手工单数已排除终态工单")
    void assignableStaff_sameTier_sortedByActiveOrderCount() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectList(any())).thenReturn(List.of(
                    staff(1L, "忙碌甲"), staff(2L, "空闲乙")));
            /* 调用 1：按处理人取涉及工单；调用 2：未完结工单取每单最新派单（计数） */
            when(assignmentMapper.selectList(any())).thenReturn(
                    List.of(assignment(10L, 1L), assignment(11L, 1L)),
                    List.of(assignment(10L, 1L), assignment(11L, 1L)));
            when(workOrderMapper.selectList(any())).thenReturn(List.of(
                    workOrder(10L, WorkOrderStatus.IN_PROGRESS), workOrder(11L, WorkOrderStatus.ACCEPTED)));

            List<StaffOptionVO> options = workOrderService.assignableStaff(null, null);

            assertThat(options).extracting(StaffOptionVO::getActiveOrderCount).containsExactly(0, 2);
            assertThat(options.get(0).getId()).isEqualTo(2L);
            assertThat(options.get(1).getId()).isEqualTo(1L);
        }
    }

    /* ---- 列表装载：处理人姓名/班次/在手工单数 ---- */

    @Test
    @DisplayName("工单列表：装配当前处理人姓名、今日班次与在手工单数")
    void page_assemblesAssigneeDispatchFields() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenPage(workOrder(10L, WorkOrderStatus.ASSIGNED));
            /* 调用 1：列表取每单最新派单；调用 2：按处理人取涉及工单；调用 3：未完结取每单最新派单 */
            when(assignmentMapper.selectList(any())).thenReturn(
                    List.of(assignment(10L, 5L)),
                    List.of(assignment(10L, 5L), assignment(11L, 5L)),
                    List.of(assignment(10L, 5L), assignment(11L, 5L)));
            when(sysUserMapper.selectList(any())).thenReturn(List.of(staff(5L, "张服务")));
            when(staffScheduleService.shiftLabelsOn(any(), any(), any()))
                    .thenReturn(Map.of(5L, "早班"));
            when(workOrderMapper.selectList(any())).thenReturn(List.of(
                    workOrder(10L, WorkOrderStatus.ASSIGNED), workOrder(11L, WorkOrderStatus.IN_PROGRESS)));

            WorkOrderVO vo = workOrderService.page(1, 20, null, null, null, null, null,
                    null, null, null, null).getRecords().get(0);

            assertThat(vo.getAssigneeId()).isEqualTo(5L);
            assertThat(vo.getAssigneeName()).isEqualTo("张服务");
            assertThat(vo.getAssigneeShiftLabel()).isEqualTo("早班");
            assertThat(vo.getAssigneeActiveOrders()).isEqualTo(2);
        }
    }

    /* ---- 调度标记与等待时长 ---- */

    @Test
    @DisplayName("调度标记：已派单超 2 小时未接单 → OVERDUE，等待时长按状态进入时间计")
    void dispatchFlag_assignedOverTwoHours_overdue() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            WorkOrder order = workOrder(10L, WorkOrderStatus.ASSIGNED);
            order.setCreatedAt(LocalDateTime.now().minusHours(5));
            givenPage(order);
            /* 派单动作 3 小时前 → 进入 ASSIGNED 状态 180 分钟（而非提交起 300 分钟） */
            when(processMapper.selectList(any())).thenReturn(List.of(
                    process(10L, WorkOrderStatus.ASSIGNED, LocalDateTime.now().minusHours(3))));

            WorkOrderVO vo = pageFirstRecord();

            assertThat(vo.getDispatchFlag()).isEqualTo(DispatchFlag.OVERDUE);
            assertThat(vo.getWaitedMinutes()).isBetween(178L, 182L);
        }
    }

    @Test
    @DisplayName("调度标记：待受理超 30 分钟 → OVERDUE；10 分钟内新建 → NEW")
    void dispatchFlag_pendingOverThirtyMinutes_overdue_elseNew() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            WorkOrder stale = workOrder(10L, WorkOrderStatus.PENDING);
            stale.setCreatedAt(LocalDateTime.now().minusMinutes(40));
            givenPage(stale);

            assertThat(pageFirstRecord().getDispatchFlag()).isEqualTo(DispatchFlag.OVERDUE);

            WorkOrder fresh = workOrder(10L, WorkOrderStatus.PENDING);
            fresh.setCreatedAt(LocalDateTime.now().minusMinutes(10));
            givenPage(fresh);

            assertThat(pageFirstRecord().getDispatchFlag()).isEqualTo(DispatchFlag.NEW);
        }
    }

    @Test
    @DisplayName("调度标记：紧急未完结 → URGENT（优先于 NEW），终态单回落 NORMAL")
    void dispatchFlag_urgentTakesPrecedence_normalForTerminal() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            WorkOrder urgent = workOrder(10L, WorkOrderStatus.TO_ASSIGN);
            urgent.setPriority(WorkOrderPriority.URGENT);
            urgent.setCreatedAt(LocalDateTime.now().minusMinutes(5));
            givenPage(urgent);

            assertThat(pageFirstRecord().getDispatchFlag()).isEqualTo(DispatchFlag.URGENT);

            WorkOrder done = workOrder(10L, WorkOrderStatus.COMPLETED);
            done.setCreatedAt(LocalDateTime.now().minusDays(3));
            givenPage(done);

            assertThat(pageFirstRecord().getDispatchFlag()).isEqualTo(DispatchFlag.NORMAL);
        }
    }

    /* ---- 列表排序：DEFAULT 回归锁定 + WAIT_DESC/PRIORITY ---- */

    @Test
    @DisplayName("列表排序回归：不传 sort 与 sort=DEFAULT/未知值一致，均为 ORDER BY id DESC")
    void page_defaultSort_unchanged() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(workOrderMapper.selectPage(any(), any())).thenAnswer(inv -> {
                Page<WorkOrder> p = inv.getArgument(0);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });
            ArgumentCaptor<LambdaQueryWrapper<WorkOrder>> pageCaptor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);

            workOrderService.page(1, 20, null, null, null, null, null, null, null, null, null);
            workOrderService.page(1, 20, null, null, null, null, null, null, null, null, "DEFAULT");
            workOrderService.page(1, 20, null, null, null, null, null, null, null, null, "UNKNOWN");

            List<LambdaQueryWrapper<WorkOrder>> wrappers = captureWrappers();
            assertThat(wrappers).hasSize(3);
            assertThat(wrappers).allSatisfy(wrapper -> {
                assertThat(wrapper.getSqlSegment()).contains("ORDER BY id DESC");
                assertThat(wrapper.getSqlSegment()).doesNotContain("CASE WHEN");
            });
            assertThat(wrappers.get(1).getSqlSegment()).isEqualTo(wrappers.get(0).getSqlSegment());
            assertThat(wrappers.get(2).getSqlSegment()).isEqualTo(wrappers.get(0).getSqlSegment());
            verify(workOrderMapper, org.mockito.Mockito.times(3))
                    .selectPage(any(), pageCaptor.capture());
        }
    }

    @Test
    @DisplayName("列表排序：WAIT_DESC 等待时长降序（提交时间升序）；PRIORITY 紧急优先 + 提交时间升序")
    void page_waitDescAndPriority_sortSegments() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(workOrderMapper.selectPage(any(), any())).thenAnswer(inv -> {
                Page<WorkOrder> p = inv.getArgument(0);
                p.setRecords(List.of());
                p.setTotal(0);
                return p;
            });

            workOrderService.page(1, 20, null, null, null, null, null, null, null, null, "WAIT_DESC");
            workOrderService.page(1, 20, null, null, null, null, null, null, null, null, "PRIORITY");

            List<LambdaQueryWrapper<WorkOrder>> wrappers = captureWrappers();
            assertThat(wrappers.get(0).getSqlSegment())
                    .contains("ORDER BY created_at ASC,id DESC");
            assertThat(wrappers.get(1).getSqlSegment())
                    .contains("CASE WHEN priority = 'URGENT' THEN 0 ELSE 1 END")
                    .contains("created_at ASC");
        }
    }

    @Test
    @DisplayName("列表：assigneeId 无派单记录 → 空页（不执行分页查询）")
    void page_assigneeIdWithoutAssignment_returnsEmpty() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(assignmentMapper.selectList(any())).thenReturn(List.of());

            PageVO<WorkOrderVO> vo = workOrderService.page(1, 20, null, null, null, null, null,
                    null, null, 42L, null);

            assertThat(vo.getRecords()).isEmpty();
            assertThat(vo.getTotal()).isEqualTo(0);
            verify(workOrderMapper, never()).selectPage(any(), any());
        }
    }

    /* ---- 脚手架 ---- */

    private MockedStatic<com.community.residence.common.context.SecurityUtils> mockSecurity() {
        MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                mockStatic(com.community.residence.common.context.SecurityUtils.class);
        mocked.when(() -> com.community.residence.common.context.SecurityUtils
                .hasRole(anyString())).thenReturn(false);
        return mocked;
    }

    private SysUser staff(Long id, String realName) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRealName(realName);
        user.setRole("STAFF");
        user.setStatus("ACTIVE");
        return user;
    }

    private WorkOrder workOrder(Long id, String status) {
        WorkOrder order = new WorkOrder();
        order.setId(id);
        order.setOrderNo("WO" + id);
        order.setResidentId(1L);
        order.setCommunityId(1L);
        order.setCategoryId(1L);
        order.setStatus(status);
        order.setPriority(WorkOrderPriority.NORMAL);
        return order;
    }

    private WorkOrderAssignment assignment(Long workOrderId, Long assigneeId) {
        WorkOrderAssignment assignment = new WorkOrderAssignment();
        assignment.setWorkOrderId(workOrderId);
        assignment.setAssigneeId(assigneeId);
        return assignment;
    }

    private WorkOrderProcess process(Long workOrderId, String newStatus, LocalDateTime createdAt) {
        WorkOrderProcess process = new WorkOrderProcess();
        process.setWorkOrderId(workOrderId);
        process.setNewStatus(newStatus);
        process.setCreatedAt(createdAt);
        return process;
    }

    private void givenPage(WorkOrder... orders) {
        /* doAnswer：同一测试内重复装配时不会误触既有 answer（when(...) 会以 null 参数调用 mock） */
        org.mockito.Mockito.doAnswer(inv -> {
            Page<WorkOrder> p = inv.getArgument(0);
            p.setRecords(List.of(orders));
            p.setTotal(orders.length);
            return p;
        }).when(workOrderMapper).selectPage(any(), any());
    }

    private WorkOrderVO pageFirstRecord() {
        return workOrderService.page(1, 20, null, null, null, null, null, null, null, null, null)
                .getRecords().get(0);
    }

    private List<LambdaQueryWrapper<WorkOrder>> captureWrappers() {
        ArgumentCaptor<LambdaQueryWrapper<WorkOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workOrderMapper, org.mockito.Mockito.atLeastOnce()).selectPage(any(), captor.capture());
        return captor.getAllValues();
    }
}
