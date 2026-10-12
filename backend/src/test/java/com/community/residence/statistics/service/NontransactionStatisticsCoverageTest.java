package com.community.residence.statistics.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.community.entity.*;
import com.community.residence.community.mapper.*;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.*;
import com.community.residence.workorder.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 非交易统计边界；staffTrend锁定现行行为，不代表采纳待裁决的归属定义。 */
@ExtendWith(MockitoExtension.class)
class NontransactionStatisticsCoverageTest {
 @Mock CommunityMapper communityMapper; @Mock BuildingMapper buildingMapper; @Mock HouseMapper houseMapper;
 @Mock ResidentMapper residentMapper; @Mock WorkOrderMapper workOrderMapper; @Mock WorkOrderAssignmentMapper assignmentMapper;
 @Mock ServiceCategoryMapper serviceCategoryMapper; @Mock SysUserMapper sysUserMapper; @Mock WorkOrderEvaluationMapper evaluationMapper;
 @Mock ResourceReservationMapper reservationMapper; @Mock LeaseRecordMapper leaseMapper;
 @InjectMocks StatisticsService service;
 @ParameterizedTest @CsvSource({"-4,1","1,1","7,7","90,90","999,90"})
 void emptyTrendClampsWindow(int input,int expected) {
  when(assignmentMapper.selectList(any())).thenReturn(List.of());
  try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)) {
   s.when(SecurityUtils::getUserId).thenReturn(7L);Map<String,Object> r=service.staffTrend(input);
   assertThat(r.get("days")).isEqualTo(expected);assertThat((Map<?,?>)r.get("assignedTrend")).hasSize(expected);
   for(String key:List.of("todayAssigned","todayCompleted","todayProcessing","totalCompleted"))assertThat(r.get(key)).isEqualTo(0L);
   verifyNoInteractions(workOrderMapper);
  }
 }
 @ParameterizedTest @CsvSource({"PENDING,0,0","TO_ASSIGN,0,0","ASSIGNED,0,0","ACCEPTED,0,1","IN_PROGRESS,0,1","TO_CONFIRM,1,0","COMPLETED,1,0","CLOSED,1,0","REJECTED,0,0","CANCELLED,0,0"})
 void trendCurrentStatusMapping(String status,long completed,long processing) {
  WorkOrderAssignment a=new WorkOrderAssignment();a.setWorkOrderId(11L);a.setAssigneeId(7L);
  WorkOrder o=new WorkOrder();o.setId(11L);o.setStatus(status);o.setCreatedAt(LocalDate.now().atTime(12,0));
  when(assignmentMapper.selectList(any())).thenReturn(List.of(a,a));when(workOrderMapper.selectList(any())).thenReturn(List.of(o));
  try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)) {
   s.when(SecurityUtils::getUserId).thenReturn(7L);Map<String,Object> r=service.staffTrend(7);
   assertThat(r.get("todayAssigned")).isEqualTo(1L);assertThat(r.get("todayCompleted")).isEqualTo(completed);
   assertThat(r.get("todayProcessing")).isEqualTo(processing);assertThat(r.get("totalCompleted")).isEqualTo(completed);
  }
 }
 @Test void trendUsesCreatedDayNotAssignmentDay() {
  WorkOrderAssignment a=new WorkOrderAssignment();a.setWorkOrderId(11L);
  WorkOrder o=new WorkOrder();o.setStatus("COMPLETED");o.setCreatedAt(LocalDate.now().minusDays(2).atTime(12,0));
  when(assignmentMapper.selectList(any())).thenReturn(List.of(a));when(workOrderMapper.selectList(any())).thenReturn(List.of(o));
  try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)) {
   s.when(SecurityUtils::getUserId).thenReturn(7L);Map<String,Object> r=service.staffTrend(7);
   assertThat(r.get("todayCompleted")).isEqualTo(0L);
   assertThat(((Map<?,?>)r.get("completedTrend")).get(LocalDate.now().minusDays(2).toString())).isEqualTo(1L);
  }
 }
 @Test void workorderDistributionReadsBackendValues() {
  WorkOrder a=new WorkOrder();a.setStatus("PENDING");a.setCategoryId(10L);a.setPriority("NORMAL");
  WorkOrder b=new WorkOrder();b.setStatus("CLOSED");b.setCategoryId(10L);b.setPriority("URGENT");when(workOrderMapper.selectList(any())).thenReturn(List.of(a,b));
  Map<String,Object> r=service.workOrderStatistics(3L);assertThat(r.get("total")).isEqualTo(2);
  assertThat(r.get("byStatus")).isEqualTo(Map.of("PENDING",1L,"CLOSED",1L));assertThat(r.get("byPriority")).isEqualTo(Map.of("NORMAL",1L,"URGENT",1L));
 }
 @Test void residentAndResourceDistributionsIncludeEmptyResult() {
  Resident a=new Resident();a.setStatus("ACTIVE");Resident b=new Resident();b.setStatus("FROZEN");when(residentMapper.selectList(any())).thenReturn(List.of(a,b));
  assertThat(service.residentStatistics().get("byStatus")).isEqualTo(Map.of("ACTIVE",1L,"FROZEN",1L));
  ResourceReservation x=new ResourceReservation();x.setStatus("CONFIRMED");when(reservationMapper.selectList(any())).thenReturn(List.of(x));
  assertThat(service.reservationStatistics(null).get("byStatus")).isEqualTo(Map.of("CONFIRMED",1L));
  when(reservationMapper.selectList(any())).thenReturn(List.of());assertThat(service.reservationStatistics(3L).get("total")).isEqualTo(0);
 }
 @Test void communityOptionsUseRealNames() {
  Community c=new Community();c.setId(8L);c.setName("后端社区名");when(communityMapper.selectList(any())).thenReturn(List.of(c));
  assertThat(service.communityOptions()).containsExactly(Map.of("id",8L,"name","后端社区名"));
 }
 @Test void emptyDashboardExposesZeroCounts() {
  stubDashboard();when(houseMapper.selectCount(any())).thenReturn(0L);Map<String,Object> r=service.dashboard(null);
  assertThat(r.get("houseCount")).isEqualTo(0L);assertThat(r.get("occupiedHouseCount")).isEqualTo(0L);assertThat((Map<?,?>)r.get("ratingDistribution")).isEmpty();assertThat((Map<?,?>)r.get("workOrderTrend7d")).isEmpty();
 }
 @Test void dashboardUsesCountsAndRows() {
  stubDashboard();when(houseMapper.selectCount(any())).thenReturn(4L,1L,2L);
  WorkOrder o=new WorkOrder();o.setStatus("PENDING");o.setCreatedAt(LocalDate.now().atTime(10,0));when(workOrderMapper.selectList(any())).thenReturn(List.of(o));
  House h=new House();h.setStatus("VACANT");when(houseMapper.selectList(any())).thenReturn(List.of(h));
  WorkOrderEvaluation e=new WorkOrderEvaluation();e.setRating(2);when(evaluationMapper.selectList(any())).thenReturn(List.of(e));Map<String,Object> r=service.dashboard(3L);
  assertThat(r.get("houseCount")).isEqualTo(4L);assertThat(r.get("occupiedHouseCount")).isEqualTo(1L);assertThat(r.get("workOrderStatusDistribution")).isEqualTo(Map.of("PENDING",1L));
  assertThat(r.get("houseStatusDistribution")).isEqualTo(Map.of("VACANT",1L));assertThat(r.get("ratingDistribution")).isEqualTo(Map.of(2,1L));
 }
 private void stubDashboard() {
  lenient().when(communityMapper.selectCount(any())).thenReturn(1L);lenient().when(buildingMapper.selectCount(any())).thenReturn(1L);
  lenient().when(houseMapper.selectList(any())).thenReturn(List.of());lenient().when(residentMapper.selectCount(any())).thenReturn(0L);
  lenient().when(leaseMapper.selectCount(any())).thenReturn(0L);lenient().when(workOrderMapper.selectCount(any())).thenReturn(0L);lenient().when(workOrderMapper.selectList(any())).thenReturn(List.of());
  lenient().when(reservationMapper.selectCount(any())).thenReturn(0L);lenient().when(evaluationMapper.selectCount(any())).thenReturn(0L);lenient().when(evaluationMapper.selectList(any())).thenReturn(List.of());
 }
}
