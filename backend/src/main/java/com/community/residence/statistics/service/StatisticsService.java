package com.community.residence.statistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.mapper.WorkOrderMapper;
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
}
