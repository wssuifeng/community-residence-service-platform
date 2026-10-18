package com.community.residence.common.statistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.workorder.constant.WorkOrderStatus;
import com.community.residence.common.workorder.constant.WorkOrderProcessAction;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.community.entity.Building;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.common.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.reservation.entity.ResourceReservation;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.entity.WorkOrderProcess;
import com.community.residence.common.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.common.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.common.workorder.entity.WorkOrderAssignment;
import com.community.residence.common.workorder.entity.ServiceCategory;
import com.community.residence.common.auth.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运营统计业务逻辑：看板聚合查询（卡片指标 + 图表数据）。
 * P1 阶段实时聚合查询（毕设场景数据量可控）；数据级权限由拦截器
 * 对带 community_id 列的表自动过滤，resident 等无列表业务层收敛。
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final CommunityMapper communityMapper;
    private final BuildingMapper buildingMapper;
    private final HouseMapper houseMapper;
    private final ResidentMapper residentMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderAssignmentMapper assignmentMapper;
    private final WorkOrderProcessMapper processMapper;
    private final ServiceCategoryMapper serviceCategoryMapper;
    private final SysUserMapper sysUserMapper;
    private final WorkOrderEvaluationMapper evaluationMapper;
    private final ResourceReservationMapper reservationMapper;
    private final LeaseRecordMapper leaseMapper;

    /** 运营看板：16 个统计卡片 + 4 个图表数据（ADMIN，拦截器过滤社区范围） */
    public Map<String, Object> dashboard(Long communityId) {
        Map<String, Object> result = new HashMap<>();

        /* ---- 基础卡片 ---- */
        result.put("communityCount", communityMapper.selectCount(null));
        result.put("buildingCount", buildingMapper.selectCount(new LambdaQueryWrapper<Building>()
                .eq(communityId != null, Building::getCommunityId, communityId)));
        result.put("houseCount", houseMapper.selectCount(new LambdaQueryWrapper<House>()
                .eq(communityId != null, House::getCommunityId, communityId)));
        result.put("occupiedHouseCount", houseMapper.selectCount(new LambdaQueryWrapper<House>()
                .eq(communityId != null, House::getCommunityId, communityId)
                .eq(House::getStatus, "OCCUPIED")));
        result.put("residentCount", residentMapper.selectCount(null));
        result.put("activeLeaseCount", leaseMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(communityId != null, LeaseRecord::getCommunityId, communityId)
                .eq(LeaseRecord::getStatus, "ACTIVE")));
        result.put("expiringLeaseCount", leaseMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(communityId != null, LeaseRecord::getCommunityId, communityId)
                .eq(LeaseRecord::getStatus, "ACTIVE")
                .between(LeaseRecord::getEndDate, LocalDate.now(), LocalDate.now().plusDays(30))));

        /* ---- 工单卡片 ---- */
        result.put("workOrderTotal", workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId)));
        result.put("workOrderPending", workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId)
                .in(WorkOrder::getStatus, WorkOrderStatus.PENDING, WorkOrderStatus.TO_ASSIGN)));
        result.put("workOrderProcessing", workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId)
                .in(WorkOrder::getStatus, WorkOrderStatus.ASSIGNED, WorkOrderStatus.ACCEPTED,
                        WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.TO_CONFIRM)));
        result.put("workOrderCompleted", workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId)
                .in(WorkOrder::getStatus, WorkOrderStatus.COMPLETED, WorkOrderStatus.CLOSED)));

        /* ---- 预约与评价卡片 ---- */
        result.put("reservationTotal", reservationMapper.selectCount(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(communityId != null, ResourceReservation::getCommunityId, communityId)));
        result.put("reservationPending", reservationMapper.selectCount(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(communityId != null, ResourceReservation::getCommunityId, communityId)
                        .eq(ResourceReservation::getStatus, "PENDING")));
        result.put("violationCount", reservationMapper.selectCount(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(communityId != null, ResourceReservation::getCommunityId, communityId)
                        .eq(ResourceReservation::getStatus, "VIOLATED")));

        /* ---- 图表 1：近 7 日工单提交趋势 ---- */
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        List<WorkOrder> recentOrders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId)
                .ge(WorkOrder::getCreatedAt, sevenDaysAgo));
        Map<String, Long> orderTrend = recentOrders.stream().collect(Collectors.groupingBy(
                o -> o.getCreatedAt().toLocalDate().toString(), Collectors.counting()));
        result.put("workOrderTrend7d", orderTrend);

        /* ---- 图表 2：工单状态分布（饼图） ---- */
        Map<String, Long> orderStatusDist = workOrderMapper.selectList(
                        new LambdaQueryWrapper<WorkOrder>()
                                .eq(communityId != null, WorkOrder::getCommunityId, communityId))
                .stream().collect(Collectors.groupingBy(WorkOrder::getStatus, Collectors.counting()));
        result.put("workOrderStatusDistribution", orderStatusDist);

        /* ---- 图表 3：房屋状态分布（饼图） ---- */
        Map<String, Long> houseStatusDist = houseMapper.selectList(
                        new LambdaQueryWrapper<House>()
                                .eq(communityId != null, House::getCommunityId, communityId))
                .stream().collect(Collectors.groupingBy(House::getStatus, Collectors.counting()));
        result.put("houseStatusDistribution", houseStatusDist);

        /* ---- 图表 4：评价分档分布（柱状） ---- */
        Map<Integer, Long> ratingDist = evaluationMapper.selectList(
                        new LambdaQueryWrapper<WorkOrderEvaluation>()
                                .eq(communityId != null, WorkOrderEvaluation::getCommunityId, communityId))
                .stream().collect(Collectors.groupingBy(WorkOrderEvaluation::getRating, Collectors.counting()));
        result.put("ratingDistribution", ratingDist);

        /* ---- 评价总览 ---- */
        result.put("evaluationTotal", evaluationMapper.selectCount(
                new LambdaQueryWrapper<WorkOrderEvaluation>()
                        .eq(communityId != null, WorkOrderEvaluation::getCommunityId, communityId)));

        return result;
    }

    /** 评价满意度双维度聚合（R39，DEF-022）：按服务人员（最新派单）/ 按服务类别分组。
        输出行：dimension 键（staffId/categoryId）+ 名称 + 评价数/平均分/满意率 */
    public Map<String, Object> evaluationAggregation(Long communityId) {
        List<WorkOrderEvaluation> evaluations = evaluationMapper.selectList(
                new LambdaQueryWrapper<WorkOrderEvaluation>()
                        .eq(communityId != null, WorkOrderEvaluation::getCommunityId, communityId));

        /* 工单 → 类别 与 工单 → 最新派单服务人员 的映射（一次载入） */
        List<Long> orderIds = evaluations.stream().map(WorkOrderEvaluation::getWorkOrderId).toList();
        Map<Long, Long> orderCategory = orderIds.isEmpty() ? Map.of()
                : workOrderMapper.selectBatchIds(orderIds).stream()
                        .collect(Collectors.toMap(WorkOrder::getId, WorkOrder::getCategoryId));
        Map<Long, Long> orderStaff = orderIds.isEmpty() ? Map.of()
                : assignmentMapper.selectList(new LambdaQueryWrapper<WorkOrderAssignment>()
                                .in(WorkOrderAssignment::getWorkOrderId, orderIds)
                                .orderByAsc(WorkOrderAssignment::getId)).stream()
                        .collect(Collectors.toMap(WorkOrderAssignment::getWorkOrderId,
                                WorkOrderAssignment::getAssigneeId, (a, b) -> b));

        Map<Long, String> staffNames = sysUserMapper.selectList(new LambdaQueryWrapper<com.community.residence.common.auth.entity.SysUser>()
                        .eq(com.community.residence.common.auth.entity.SysUser::getRole, RoleConstants.STAFF))
                .stream().collect(Collectors.toMap(
                        com.community.residence.common.auth.entity.SysUser::getId,
                        com.community.residence.common.auth.entity.SysUser::getRealName));
        Map<Long, String> categoryNames = serviceCategoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(ServiceCategory::getId, ServiceCategory::getName));

        Map<String, Object> result = new HashMap<>();
        result.put("byStaff", aggregate(evaluations,
                e -> orderStaff.containsKey(e.getWorkOrderId())
                        ? "staff-" + orderStaff.get(e.getWorkOrderId()) : null,
                id -> staffNames.getOrDefault(Long.parseLong(id.substring(6)), "未知服务人员")));
        result.put("byCategory", aggregate(evaluations,
                e -> orderCategory.containsKey(e.getWorkOrderId())
                        ? "category-" + orderCategory.get(e.getWorkOrderId()) : null,
                id -> categoryNames.getOrDefault(Long.parseLong(id.substring(9)), "未知类别")));
        return result;
    }

    /* 分组聚合骨架：total/avgRating/satisfiedRate 三指标 + 名称回填 */
    private List<Map<String, Object>> aggregate(List<WorkOrderEvaluation> evaluations,
                                                java.util.function.Function<WorkOrderEvaluation, String> keyFn,
                                                java.util.function.Function<String, String> nameFn) {
        Map<String, List<WorkOrderEvaluation>> grouped = evaluations.stream()
                .filter(e -> keyFn.apply(e) != null)
                .collect(Collectors.groupingBy(keyFn));
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, List<WorkOrderEvaluation>> entry : grouped.entrySet()) {
            List<WorkOrderEvaluation> group = entry.getValue();
            Map<String, Object> row = new HashMap<>();
            row.put("total", (long) group.size());
            row.put("avgRating", group.stream().mapToInt(WorkOrderEvaluation::getRating).average().orElse(0));
            row.put("satisfiedRate", group.stream()
                    .filter(e -> e.getIsSatisfied() != null && e.getIsSatisfied() == 1).count() * 100.0 / group.size());
            row.put("name", nameFn.apply(entry.getKey()));
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("total"), (long) a.get("total")));
        return rows;
    }

    /** 工单统计（专项）：状态分布 + 优先级分布 */
    public Map<String, Object> workOrderStatistics(Long communityId) {
        List<WorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId));
        Map<String, Object> result = new HashMap<>();
        result.put("total", orders.size());
        result.put("byStatus", orders.stream().collect(
                Collectors.groupingBy(WorkOrder::getStatus, Collectors.counting())));
        result.put("byPriority", orders.stream().collect(
                Collectors.groupingBy(WorkOrder::getPriority, Collectors.counting())));
        return result;
    }

    /** 居民统计（专项）：总量与状态分布 */
    public Map<String, Object> residentStatistics() {
        List<Resident> residents = residentMapper.selectList(null);
        Map<String, Object> result = new HashMap<>();
        result.put("total", residents.size());
        result.put("byStatus", residents.stream().collect(
                Collectors.groupingBy(Resident::getStatus, Collectors.counting())));
        return result;
    }

    /** 资源预约统计（专项）：状态分布 */
    public Map<String, Object> reservationStatistics(Long communityId) {
        List<ResourceReservation> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(communityId != null, ResourceReservation::getCommunityId, communityId));
        Map<String, Object> result = new HashMap<>();
        result.put("total", reservations.size());
        result.put("byStatus", reservations.stream().collect(
                Collectors.groupingBy(ResourceReservation::getStatus, Collectors.counting())));
        return result;
    }

    /** 社区列表（看板社区筛选下拉） */
    public List<Map<String, Object>> communityOptions() {
        return communityMapper.selectList(null).stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName()))
                .toList();
    }

    /* 当前负荷由最新派单列表负责；本接口只统计实际发生的本人事件。
       派入按assignTime、提交结果按STAFF操作时间线归日，同一工单的真实重派/重做可计多次。
       查询窗口为本地日半开区间，不拿创建日期/当前状态或他人动作推测过去工作量。 */
    public Map<String, Object> staffTrend(int days) {
        Long staffId = SecurityUtils.getUserId();
        int window = Math.min(Math.max(days, 1), 90);
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(window - 1L);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        List<WorkOrderAssignment> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<WorkOrderAssignment>()
                        .eq(WorkOrderAssignment::getAssigneeId, staffId)
                        .ge(WorkOrderAssignment::getAssignTime, start)
                        .lt(WorkOrderAssignment::getAssignTime, end));
        List<WorkOrderProcess> submissions = processMapper.selectList(
                new LambdaQueryWrapper<WorkOrderProcess>()
                        .eq(WorkOrderProcess::getOperatorId, staffId)
                        .eq(WorkOrderProcess::getOperatorType, RoleConstants.STAFF)
                        .eq(WorkOrderProcess::getAction, WorkOrderProcessAction.COMPLETE)
                        .eq(WorkOrderProcess::getNewStatus, WorkOrderStatus.TO_CONFIRM)
                        .ge(WorkOrderProcess::getCreatedAt, start)
                        .lt(WorkOrderProcess::getCreatedAt, end));

        /* 子表无community_id，先经工单主表的统一数据范围取得可见ID；
           仅查本窗口本人事件引用的对象，不用全部历史派单扩大当前职责。 */
        java.util.Set<Long> referencedIds = new java.util.HashSet<>();
        assignments.forEach(event -> referencedIds.add(event.getWorkOrderId()));
        submissions.forEach(event -> referencedIds.add(event.getWorkOrderId()));
        java.util.Set<Long> visibleIds = referencedIds.isEmpty() ? java.util.Set.of()
                : workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                        .select(WorkOrder::getId).in(WorkOrder::getId, referencedIds))
                        .stream().map(WorkOrder::getId).collect(Collectors.toSet());

        Map<String, Long> assignedTrend = emptyDailyEvents(startDate, window);
        Map<String, Long> submittedTrend = emptyDailyEvents(startDate, window);
        for (WorkOrderAssignment event : assignments) {
            if (visibleIds.contains(event.getWorkOrderId())) {
                addDailyEvent(assignedTrend, event.getAssignTime());
            }
        }
        for (WorkOrderProcess event : submissions) {
            if (visibleIds.contains(event.getWorkOrderId())) {
                addDailyEvent(submittedTrend, event.getCreatedAt());
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("days", window);
        result.put("startDate", startDate.toString());
        result.put("endDate", today.toString());
        result.put("assignedTrend", assignedTrend);
        result.put("submittedTrend", submittedTrend);
        result.put("todayAssigned", assignedTrend.get(today.toString()));
        result.put("todaySubmitted", submittedTrend.get(today.toString()));
        result.put("totalSubmitted", submittedTrend.values().stream().mapToLong(Long::longValue).sum());
        return result;
    }

    private Map<String, Long> emptyDailyEvents(LocalDate start, int window) {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            result.put(start.plusDays(i).toString(), 0L);
        }
        return result;
    }

    private void addDailyEvent(Map<String, Long> events, LocalDateTime occurredAt) {
        if (occurredAt != null) {
            events.computeIfPresent(occurredAt.toLocalDate().toString(), (day, count) -> count + 1L);
        }
    }
}
