package com.community.residence.statistics.service;

import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * DEF-022 修复回归（R39 双维度聚合）：按服务人员（经最新派单）与按服务类别的
 * 评价数/平均分/满意率聚合，数值与明细一致。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价双维度聚合修复回归（DEF-022）")
class EvaluationAggregationFixTest {

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderAssignmentMapper assignmentMapper;
    @Mock
    private ServiceCategoryMapper serviceCategoryMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private WorkOrderEvaluationMapper evaluationMapper;
    @Mock
    private com.community.residence.reservation.mapper.ResourceReservationMapper reservationMapper2;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private CommunityMapper communityMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private LeaseRecordMapper leaseRecordMapper;

    @InjectMocks
    private StatisticsService service;

    @Test
    @DisplayName("by-staff：按最新派单人分组聚合（评价数/平均分/满意率）")
    void aggregation_byStaff() {
        stubDomain(
                /* 评价：工单 1（staff7 打 5 分满意）、工单 2（staff7 打 3 分满意）、工单 3（staff8 打 1 分不满意） */
                List.of(evaluation(1L, 5, 1), evaluation(2L, 3, 1), evaluation(3L, 1, 0)),
                List.of(order(1L, 10L), order(2L, 11L), order(3L, 12L)),
                /* 工单 1 曾改派（staff6 → staff7 取 id 更大者）；工单 2 派 staff7；工单 3 派 staff8 */
                List.of(assignment(1L, 6L, 1), assignment(1L, 7L, 2),
                        assignment(2L, 7L, 3), assignment(3L, 8L, 4)),
                List.of(category(10L, "水电维修"), category(11L, "卫生保洁"), category(12L, "电梯维保")),
                List.of(staff(6L, "旧师傅"), staff(7L, "张师傅"), staff(8L, "李师傅")));

        Map<String, Object> result = service.evaluationAggregation(null);

        @SuppressWarnings("unchecked")
        /* 改派后工单 1 归属最新派单人 staff7——旧师傅（staff6）名下无该单，仅 2 组 */
        List<Map<String, Object>> byStaff = (List<Map<String, Object>>) result.get("byStaff");
        assertThat(byStaff).hasSize(2);
        Map<String, Object> zhang = byStaff.stream()
                .filter(r -> "张师傅".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(((Number) zhang.get("total")).longValue()).isEqualTo(2L);
        assertThat((double) zhang.get("avgRating")).isEqualTo(4.0);
        assertThat((double) zhang.get("satisfiedRate")).isEqualTo(100.0);

        Map<String, Object> li = byStaff.stream()
                .filter(r -> "李师傅".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(((Number) li.get("total")).longValue()).isEqualTo(1L);
        assertThat((double) li.get("avgRating")).isEqualTo(1.0);
        assertThat((double) li.get("satisfiedRate")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("by-category：按工单类别分组聚合")
    void aggregation_byCategory() {
        stubDomain(
                List.of(evaluation(1L, 5, 1), evaluation(2L, 3, 0)),
                List.of(order(1L, 10L), order(2L, 10L)),
                List.of(assignment(1L, 7L, 1), assignment(2L, 7L, 2)),
                List.of(category(10L, "水电维修"), category(11L, "卫生保洁")),
                List.of(staff(7L, "张师傅")));

        Map<String, Object> result = service.evaluationAggregation(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCategory = (List<Map<String, Object>>) result.get("byCategory");
        assertThat(byCategory).hasSize(1);
        Map<String, Object> row = byCategory.get(0);
        assertThat(row.get("name")).isEqualTo("水电维修");
        assertThat(((Number) row.get("total")).longValue()).isEqualTo(2L);
        assertThat((double) row.get("avgRating")).isEqualTo(4.0);
        assertThat((double) row.get("satisfiedRate")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("社区过滤与空数据：无评价时两维度均为空列表")
    void aggregation_empty() {
        lenient().when(evaluationMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.evaluationAggregation(1L);

        assertThat((List<?>) result.get("byStaff")).isEmpty();
        assertThat((List<?>) result.get("byCategory")).isEmpty();
    }

    /* ---- 通用脚手架 ---- */

    private void stubDomain(List<WorkOrderEvaluation> evaluations, List<WorkOrder> orders,
                            List<WorkOrderAssignment> assignments, List<ServiceCategory> categories,
                            List<SysUser> staffs) {
        lenient().when(evaluationMapper.selectList(any())).thenReturn(evaluations);
        lenient().when(workOrderMapper.selectBatchIds(any()))
                .thenReturn(orders);
        lenient().when(assignmentMapper.selectList(any())).thenReturn(assignments);
        lenient().when(serviceCategoryMapper.selectList(any())).thenReturn(categories);
        lenient().when(sysUserMapper.selectList(any())).thenReturn(staffs);
    }

    private WorkOrderEvaluation evaluation(Long orderId, int rating, int satisfied) {
        WorkOrderEvaluation e = new WorkOrderEvaluation();
        e.setWorkOrderId(orderId);
        e.setCommunityId(1L);
        e.setRating(rating);
        e.setIsSatisfied(satisfied);
        return e;
    }

    private WorkOrder order(Long id, Long categoryId) {
        WorkOrder o = new WorkOrder();
        o.setId(id);
        o.setCategoryId(categoryId);
        return o;
    }

    private WorkOrderAssignment assignment(Long orderId, Long assigneeId, long id) {
        WorkOrderAssignment a = new WorkOrderAssignment();
        a.setId(id);
        a.setWorkOrderId(orderId);
        a.setAssigneeId(assigneeId);
        return a;
    }

    private ServiceCategory category(Long id, String name) {
        ServiceCategory c = new ServiceCategory();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private SysUser staff(Long id, String name) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setRealName(name);
        u.setRole("STAFF");
        return u;
    }
}
