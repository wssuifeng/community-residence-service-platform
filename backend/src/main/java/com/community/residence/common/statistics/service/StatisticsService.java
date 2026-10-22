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
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.entity.ViewingAppointment;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.common.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.reservation.entity.ResourceReservation;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.statistics.entity.StatisticsSnapshot;
import com.community.residence.common.statistics.mapper.StatisticsSnapshotMapper;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final HousingMapper housingMapper;
    private final ViewingAppointmentMapper viewingAppointmentMapper;
    private final ResidentMapper residentMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderAssignmentMapper assignmentMapper;
    private final WorkOrderProcessMapper processMapper;
    private final ServiceCategoryMapper serviceCategoryMapper;
    private final SysUserMapper sysUserMapper;
    private final WorkOrderEvaluationMapper evaluationMapper;
    private final ResourceReservationMapper reservationMapper;
    private final LeaseRecordMapper leaseMapper;
    private final StatisticsSnapshotMapper statisticsSnapshotMapper;

    /** Backward-compatible dashboard entry: defaults to the last 30 calendar days. */
    public Map<String, Object> dashboard(Long communityId) {
        return dashboard(communityId, StatisticsDateRange.resolve(null, null, LocalDate.now()));
    }

    /** C9 dashboard: current snapshots are separated from events in the selected period. */
    public Map<String, Object> dashboard(Long communityId, StatisticsDateRange range) {
        LocalDateTime start = range.startDate().atStartOfDay();
        LocalDateTime end = range.endExclusive().atStartOfDay();
        List<WorkOrder> allOrders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId));
        List<WorkOrder> periodOrders = allOrders.stream()
                .filter(order -> inRange(order.getCreatedAt(), start, end)).toList();
        Set<Long> periodOrderIds = periodOrders.stream().map(WorkOrder::getId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        Map<String, Object> result = new LinkedHashMap<>();
        List<House> houses = houseMapper.selectList(new LambdaQueryWrapper<House>()
                .eq(communityId != null, House::getCommunityId, communityId));
        List<LeaseRecord> activeLeases = leaseMapper.selectList(new LambdaQueryWrapper<LeaseRecord>()
                .eq(communityId != null, LeaseRecord::getCommunityId, communityId)
                .eq(LeaseRecord::getStatus, "ACTIVE"));
        List<ResidenceRelation> relations = residenceRelationMapper.selectList(
                new LambdaQueryWrapper<ResidenceRelation>()
                        .eq(communityId != null, ResidenceRelation::getCommunityId, communityId));
        Set<Long> currentResidents = relations.stream()
                .filter(relation -> relation.getMoveOutDate() == null)
                .map(ResidenceRelation::getResidentId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, Long> houseStatusDist = houses.stream().collect(Collectors.groupingBy(
                House::getStatus, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> orderStatusDist = allOrders.stream().collect(Collectors.groupingBy(
                WorkOrder::getStatus, LinkedHashMap::new, Collectors.counting()));
        List<WorkOrderProcess> completionEvents = periodOrderIds.isEmpty() ? List.of()
                : processMapper.selectList(new LambdaQueryWrapper<WorkOrderProcess>()
                        .in(WorkOrderProcess::getWorkOrderId, periodOrderIds)
                        .eq(WorkOrderProcess::getOperatorType, RoleConstants.STAFF)
                        .eq(WorkOrderProcess::getAction, WorkOrderProcessAction.COMPLETE)
                        .eq(WorkOrderProcess::getNewStatus, WorkOrderStatus.TO_CONFIRM)
                        .ge(WorkOrderProcess::getCreatedAt, start)
                        .lt(WorkOrderProcess::getCreatedAt, end));
        Set<Long> completedPeriodIds = completionEvents.stream().map(WorkOrderProcess::getWorkOrderId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, LocalDateTime> firstCompletionByOrder = new HashMap<>();
        for (WorkOrderProcess event : completionEvents) {
            if (event.getWorkOrderId() == null || event.getCreatedAt() == null) continue;
            firstCompletionByOrder.merge(event.getWorkOrderId(), event.getCreatedAt(),
                    (left, right) -> left.isBefore(right) ? left : right);
        }
        double avgProcessingHours = periodOrders.stream()
                .filter(order -> order.getId() != null && order.getCreatedAt() != null
                        && firstCompletionByOrder.containsKey(order.getId()))
                .mapToDouble(order -> java.time.Duration.between(order.getCreatedAt(),
                        firstCompletionByOrder.get(order.getId())).toMinutes() / 60.0)
                .average().orElse(0.0);

        List<ResourceReservation> periodReservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(communityId != null, ResourceReservation::getCommunityId, communityId)
                        .ge(ResourceReservation::getReserveDate, range.startDate())
                        .le(ResourceReservation::getReserveDate, range.endDate()));
        List<ViewingAppointment> periodViewings = viewingAppointmentMapper.selectList(
                new LambdaQueryWrapper<ViewingAppointment>()
                        .eq(communityId != null, ViewingAppointment::getCommunityId, communityId)
                        .ge(ViewingAppointment::getAppointmentDate, range.startDate())
                        .le(ViewingAppointment::getAppointmentDate, range.endDate()));
        List<WorkOrderEvaluation> periodEvaluations = evaluationMapper.selectList(
                new LambdaQueryWrapper<WorkOrderEvaluation>()
                        .eq(communityId != null, WorkOrderEvaluation::getCommunityId, communityId)
                        .ge(WorkOrderEvaluation::getCreatedAt, start)
                        .lt(WorkOrderEvaluation::getCreatedAt, end));
        Map<Integer, Long> ratingDist = periodEvaluations.stream().collect(Collectors.groupingBy(
                WorkOrderEvaluation::getRating, LinkedHashMap::new, Collectors.counting()));
        double avgRating = periodEvaluations.stream().mapToInt(WorkOrderEvaluation::getRating)
                .average().orElse(0.0);
        double satisfactionRate = periodEvaluations.isEmpty() ? 0.0
                : periodEvaluations.stream().filter(e -> Integer.valueOf(1).equals(e.getIsSatisfied())).count()
                * 100.0 / periodEvaluations.size();

        Map<String, Long> orderTrend = emptyDailyEvents(range);
        periodOrders.forEach(order -> addDailyEvent(orderTrend, order.getCreatedAt()));
        Map<String, Long> viewTrend = emptyDailyEvents(range);
        if (statisticsSnapshotMapper != null) {
            for (StatisticsSnapshot snapshot : statisticsSnapshotMapper.selectHousingViews(
                    communityId, range.startDate(), range.endExclusive())) {
                if (snapshot.getSnapshotDate() != null) {
                    viewTrend.merge(snapshot.getSnapshotDate().toString(),
                            snapshot.getMetricValue() == null ? 0L : snapshot.getMetricValue(), Long::sum);
                }
            }
        }

        List<ResidenceRelation> scopedRelations = relations;
        long moveIn = scopedRelations.stream().filter(r -> range.contains(r.getMoveInDate())).count();
        long moveOut = scopedRelations.stream().filter(r -> range.contains(r.getMoveOutDate())).count();
        long reservationCompleted = periodReservations.stream()
                .filter(r -> "COMPLETED".equals(r.getStatus())).count();
        long viewingCompleted = periodViewings.stream()
                .filter(v -> "COMPLETED".equals(v.getStatus())).count();

        Map<String, Long> assignedByStaff = new HashMap<>();
        Map<String, Long> submittedByStaff = new HashMap<>();
        List<WorkOrderAssignment> assignments = periodOrderIds.isEmpty() ? List.of()
                : assignmentMapper.selectList(new LambdaQueryWrapper<WorkOrderAssignment>()
                        .in(WorkOrderAssignment::getWorkOrderId, periodOrderIds)
                        .ge(WorkOrderAssignment::getAssignTime, start)
                        .lt(WorkOrderAssignment::getAssignTime, end));
        assignments.forEach(a -> increment(assignedByStaff, a.getAssigneeId()));
        completionEvents.forEach(e -> increment(submittedByStaff, e.getOperatorId()));
        Set<Long> staffIds = new HashSet<>();
        assignedByStaff.keySet().forEach(id -> staffIds.add(Long.valueOf(id)));
        submittedByStaff.keySet().forEach(id -> staffIds.add(Long.valueOf(id)));
        Map<Long, String> staffNames = staffIds.isEmpty() ? Map.of() : sysUserMapper.selectBatchIds(staffIds).stream()
                .collect(Collectors.toMap(com.community.residence.common.auth.entity.SysUser::getId,
                        com.community.residence.common.auth.entity.SysUser::getRealName));
        List<Map<String, Object>> staffWorkload = staffIds.stream().map(id -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("staffId", id);
            row.put("name", staffNames.getOrDefault(id, "未知服务人员"));
            row.put("assignedCount", assignedByStaff.getOrDefault(String.valueOf(id), 0L));
            row.put("completedCount", submittedByStaff.getOrDefault(String.valueOf(id), 0L));
            row.put("total", assignedByStaff.getOrDefault(String.valueOf(id), 0L)
                    + submittedByStaff.getOrDefault(String.valueOf(id), 0L));
            return row;
        }).sorted((a, b) -> Long.compare((Long) b.get("total"), (Long) a.get("total"))).toList();

        result.put("startDate", range.startDate().toString());
        result.put("endDate", range.endDate().toString());
        result.put("communityCount", communityId == null ? communityMapper.selectCount(null) : 1L);
        result.put("buildingCount", buildingMapper.selectCount(new LambdaQueryWrapper<Building>()
                .eq(communityId != null, Building::getCommunityId, communityId)));
        result.put("houseCount", houses.size());
        result.put("occupiedHouseCount", houses.stream().filter(h -> "OWNER_OCCUPIED".equals(h.getStatus())
                || "TENANT_OCCUPIED".equals(h.getStatus())).count());
        result.put("residentCount", currentResidents.size());
        result.put("activeLeaseCount", activeLeases.size());
        result.put("expiringLeaseCount", activeLeases.stream().filter(l -> l.getEndDate() != null
                && !l.getEndDate().isBefore(LocalDate.now()) && !l.getEndDate().isAfter(LocalDate.now().plusDays(30))).count());
        result.put("workOrderTotal", allOrders.size());
        result.put("workOrderPending", allOrders.stream().filter(o -> WorkOrderStatus.PENDING.equals(o.getStatus())
                || WorkOrderStatus.TO_ASSIGN.equals(o.getStatus())).count());
        result.put("workOrderProcessing", allOrders.stream().filter(o -> Set.of(WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.ACCEPTED, WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.TO_CONFIRM).contains(o.getStatus())).count());
        result.put("workOrderCompleted", allOrders.stream().filter(o -> WorkOrderStatus.COMPLETED.equals(o.getStatus())
                || WorkOrderStatus.CLOSED.equals(o.getStatus())).count());
        result.put("reservationTotal", periodReservations.size());
        result.put("reservationPending", periodReservations.stream().filter(r -> "PENDING".equals(r.getStatus())).count());
        result.put("violationCount", periodReservations.stream().filter(r -> "VIOLATED".equals(r.getStatus())).count());
        result.put("evaluationTotal", periodEvaluations.size());
        result.put("workOrderPeriodCount", periodOrders.size());
        result.put("workOrderPeriodCompletedCount", completedPeriodIds.size());
        result.put("workOrderCompletionRate", periodOrders.isEmpty() ? 0.0 : completedPeriodIds.size() * 100.0 / periodOrders.size());
        result.put("workOrderAvgProcessingHours", avgProcessingHours);
        result.put("residentMoveInTotal", moveIn);
        result.put("residentMoveOutTotal", moveOut);
        result.put("reservationPeriodTotal", periodReservations.size());
        result.put("reservationCompletedTotal", reservationCompleted);
        result.put("reservationCompletionRate", periodReservations.isEmpty() ? 0.0 : reservationCompleted * 100.0 / periodReservations.size());
        result.put("viewingPeriodTotal", periodViewings.size());
        result.put("viewingCompletedTotal", viewingCompleted);
        result.put("housingViewPeriodTotal", viewTrend.values().stream().mapToLong(Long::longValue).sum());
        result.put("housingViewTrend", viewTrend);
        result.put("evaluationPeriodTotal", periodEvaluations.size());
        result.put("evaluationAvgRating", avgRating);
        result.put("evaluationSatisfactionRate", satisfactionRate);
        result.put("staffWorkload", staffWorkload);
        result.put("workOrderTrend7d", orderTrend);
        result.put("workOrderStatusDistribution", orderStatusDist);
        result.put("houseStatusDistribution", houseStatusDist);
        result.put("ratingDistribution", ratingDist);
        return result;
    }

    /** 评价满意度双维度聚合（R39，DEF-022）：按服务人员（最新派单）/ 按服务类别分组。
        输出行：dimension 键（staffId/categoryId）+ 名称 + 评价数/平均分/满意率 */
    public Map<String, Object> evaluationAggregation(Long communityId) {
        return evaluationAggregation(communityId,
                StatisticsDateRange.resolve(null, null, LocalDate.now()));
    }

    public Map<String, Object> evaluationAggregation(Long communityId, StatisticsDateRange range) {
        List<WorkOrderEvaluation> evaluations = evaluationMapper.selectList(
                new LambdaQueryWrapper<WorkOrderEvaluation>()
                        .eq(communityId != null, WorkOrderEvaluation::getCommunityId, communityId)
                        .ge(WorkOrderEvaluation::getCreatedAt, range.startDate().atStartOfDay())
                        .lt(WorkOrderEvaluation::getCreatedAt, range.endExclusive().atStartOfDay()));

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
        return workOrderStatistics(communityId,
                StatisticsDateRange.resolve(null, null, LocalDate.now()));
    }

    public Map<String, Object> workOrderStatistics(Long communityId, StatisticsDateRange range) {
        List<WorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(communityId != null, WorkOrder::getCommunityId, communityId)
                .ge(WorkOrder::getCreatedAt, range.startDate().atStartOfDay())
                .lt(WorkOrder::getCreatedAt, range.endExclusive().atStartOfDay()));
        Set<Long> orderIds = orders.stream().map(WorkOrder::getId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        List<WorkOrderProcess> completionEvents = orderIds.isEmpty() ? List.of()
                : processMapper.selectList(new LambdaQueryWrapper<WorkOrderProcess>()
                        .in(WorkOrderProcess::getWorkOrderId, orderIds)
                        .eq(WorkOrderProcess::getOperatorType, RoleConstants.STAFF)
                        .eq(WorkOrderProcess::getAction, WorkOrderProcessAction.COMPLETE)
                        .eq(WorkOrderProcess::getNewStatus, WorkOrderStatus.TO_CONFIRM)
                        .ge(WorkOrderProcess::getCreatedAt, range.startDate().atStartOfDay())
                        .lt(WorkOrderProcess::getCreatedAt, range.endExclusive().atStartOfDay()));
        Set<Long> completedIds = completionEvents.stream().map(WorkOrderProcess::getWorkOrderId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, LocalDateTime> firstCompletion = new HashMap<>();
        completionEvents.forEach(event -> {
            if (event.getWorkOrderId() != null && event.getCreatedAt() != null) {
                firstCompletion.merge(event.getWorkOrderId(), event.getCreatedAt(),
                        (left, right) -> left.isBefore(right) ? left : right);
            }
        });
        double averageProcessingHours = orders.stream()
                .filter(order -> order.getId() != null && order.getCreatedAt() != null
                        && firstCompletion.containsKey(order.getId()))
                .mapToDouble(order -> java.time.Duration.between(order.getCreatedAt(),
                        firstCompletion.get(order.getId())).toMinutes() / 60.0)
                .average().orElse(0.0);
        Map<String, Object> result = new HashMap<>();
        result.put("total", orders.size());
        result.put("completed", completedIds.size());
        result.put("completionRate", orders.isEmpty() ? 0.0 : completedIds.size() * 100.0 / orders.size());
        result.put("averageProcessingHours", averageProcessingHours);
        result.put("startDate", range.startDate().toString());
        result.put("endDate", range.endDate().toString());
        result.put("byStatus", orders.stream().collect(
                Collectors.groupingBy(WorkOrder::getStatus, Collectors.counting())));
        result.put("byPriority", orders.stream().collect(
                Collectors.groupingBy(WorkOrder::getPriority, Collectors.counting())));
        return result;
    }

    /** 居民统计（专项）：总量与状态分布 */
    public Map<String, Object> residentStatistics() {
        return residentStatistics(null, StatisticsDateRange.resolve(null, null, LocalDate.now()));
    }

    public Map<String, Object> residentStatistics(Long communityId, StatisticsDateRange range) {
        List<ResidenceRelation> relations = residenceRelationMapper.selectList(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(communityId != null, ResidenceRelation::getCommunityId, communityId));
        Set<Long> residentIds = relations.stream().map(ResidenceRelation::getResidentId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        List<Resident> residents = residentIds.isEmpty() ? List.of() : residentMapper.selectBatchIds(residentIds);
        long current = relations.stream().filter(relation -> relation.getMoveOutDate() == null)
                .map(ResidenceRelation::getResidentId).filter(java.util.Objects::nonNull).distinct().count();
        long moveIn = relations.stream().filter(relation -> range.contains(relation.getMoveInDate())).count();
        long moveOut = relations.stream().filter(relation -> range.contains(relation.getMoveOutDate())).count();
        Map<String, Object> result = new HashMap<>();
        result.put("total", current);
        result.put("moveInTotal", moveIn);
        result.put("moveOutTotal", moveOut);
        result.put("startDate", range.startDate().toString());
        result.put("endDate", range.endDate().toString());
        result.put("byStatus", residents.stream().collect(
                Collectors.groupingBy(Resident::getStatus, Collectors.counting())));
        return result;
    }

    /** 资源预约统计（专项）：状态分布 */
    public Map<String, Object> reservationStatistics(Long communityId) {
        return reservationStatistics(communityId,
                StatisticsDateRange.resolve(null, null, LocalDate.now()));
    }

    public Map<String, Object> reservationStatistics(Long communityId, StatisticsDateRange range) {
        List<ResourceReservation> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(communityId != null, ResourceReservation::getCommunityId, communityId)
                        .ge(ResourceReservation::getReserveDate, range.startDate())
                        .le(ResourceReservation::getReserveDate, range.endDate()));
        Map<String, Object> result = new HashMap<>();
        result.put("total", reservations.size());
        result.put("completed", reservations.stream().filter(r -> "COMPLETED".equals(r.getStatus())).count());
        result.put("startDate", range.startDate().toString());
        result.put("endDate", range.endDate().toString());
        result.put("byStatus", reservations.stream().collect(
                Collectors.groupingBy(ResourceReservation::getStatus, Collectors.counting())));
        return result;
    }

    private Map<String, Long> emptyDailyEvents(StatisticsDateRange range) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (LocalDate date = range.startDate(); !date.isAfter(range.endDate()); date = date.plusDays(1)) {
            result.put(date.toString(), 0L);
        }
        return result;
    }

    private boolean inRange(LocalDateTime occurredAt, LocalDateTime start, LocalDateTime end) {
        return occurredAt != null && !occurredAt.isBefore(start) && occurredAt.isBefore(end);
    }

    private void increment(Map<String, Long> counts, Long id) {
        if (id != null) counts.merge(id.toString(), 1L, Long::sum);
    }

    private void addDailyEvent(Map<String, Long> events, LocalDateTime occurredAt) {
        if (occurredAt != null) {
            events.computeIfPresent(occurredAt.toLocalDate().toString(), (day, count) -> count + 1L);
        }
    }

    /** 小区列表（看板小区筛选下拉） */
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
                        .in(WorkOrder::getId, referencedIds))
                        .stream().map(WorkOrder::getId).collect(Collectors.toSet());

        StatisticsDateRange range = StatisticsDateRange.resolve(startDate, today, today);
        Map<String, Long> assignedTrend = emptyDailyEvents(range);
        Map<String, Long> submittedTrend = emptyDailyEvents(range);
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

}
