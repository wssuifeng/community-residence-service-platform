package com.community.residence.common.statistics.service;

import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.workorder.entity.WorkOrderAssignment;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.entity.WorkOrderProcess;
import com.community.residence.common.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 已批准事件口径：不以创建日或当前状态冒充本人历史成果。 */
@ExtendWith(MockitoExtension.class)
class StaffWorkloadEventRegressionTest {
    @Mock WorkOrderAssignmentMapper assignmentMapper;
    @Mock WorkOrderProcessMapper processMapper;
    @Mock WorkOrderMapper workOrderMapper;
    @InjectMocks StatisticsService service;

    @Test void repeatedAssignmentsAndResultSubmissionsAreRealEvents() {
        WorkOrderAssignment first = assignment(LocalDate.now().minusDays(2));
        WorkOrderAssignment second = assignment(LocalDate.now());
        when(assignmentMapper.selectList(any())).thenReturn(List.of(first, second));
        WorkOrder parent = new WorkOrder(); parent.setId(11L); parent.setStatus("ASSIGNED");
        when(workOrderMapper.selectList(any())).thenReturn(List.of(parent));
        when(processMapper.selectList(any())).thenReturn(List.of(result(LocalDate.now()), result(LocalDate.now())));
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(SecurityUtils::getUserId).thenReturn(7L);
            Map<String,Object> data = service.staffTrend(7);
            assertThat(data.get("todayAssigned")).isEqualTo(1L);
            assertThat(data.get("todaySubmitted")).isEqualTo(2L);
            assertThat(data.get("totalSubmitted")).isEqualTo(2L);
            assertThat((Map<?,?>) data.get("assignedTrend")).hasSize(7);
            assertThat(((Map<?,?>) data.get("assignedTrend")).get(LocalDate.now().minusDays(2).toString())).isEqualTo(1L);
            assertThat(data).doesNotContainKeys("processingTrend", "completedTrend", "todayProcessing", "todayCompleted");
        }
    }

    @Test void hiddenParentEventsDoNotBypassCommunityScope() {
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment(LocalDate.now())));
        when(processMapper.selectList(any())).thenReturn(List.of(result(LocalDate.now())));
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(SecurityUtils::getUserId).thenReturn(7L);
            assertThat(service.staffTrend(7).get("totalSubmitted")).isEqualTo(0L);
        }
    }

    @Test void nullAndOutsideWindowDatesDoNotGrowTheDateSeries() {
        WorkOrder parent = new WorkOrder(); parent.setId(11L);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(parent));
        WorkOrderAssignment legacy = assignment(LocalDate.now()); legacy.setAssignTime(null);
        when(assignmentMapper.selectList(any())).thenReturn(List.of(legacy, assignment(LocalDate.now().minusDays(9))));
        when(processMapper.selectList(any())).thenReturn(List.of(result(LocalDate.now().plusDays(1))));
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(SecurityUtils::getUserId).thenReturn(7L);
            Map<String,Object> data = service.staffTrend(7);
            assertThat((Map<?,?>)data.get("submittedTrend")).hasSize(7);
            assertThat(data.get("totalSubmitted")).isEqualTo(0L);
            assertThat(data.get("todayAssigned")).isEqualTo(0L);
        }
    }

    private WorkOrderAssignment assignment(LocalDate day) {
        WorkOrderAssignment event = new WorkOrderAssignment();
        event.setWorkOrderId(11L); event.setAssigneeId(7L); event.setAssignTime(day.atTime(12,0));
        return event;
    }
    private WorkOrderProcess result(LocalDate day) {
        WorkOrderProcess event = new WorkOrderProcess();
        event.setWorkOrderId(11L); event.setOperatorId(7L); event.setOperatorType("STAFF");
        event.setAction("COMPLETE"); event.setNewStatus("TO_CONFIRM"); event.setCreatedAt(day.atTime(13,0));
        return event;
    }
}
