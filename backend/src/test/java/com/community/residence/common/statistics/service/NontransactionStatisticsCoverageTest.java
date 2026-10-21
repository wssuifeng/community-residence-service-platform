package com.community.residence.common.statistics.service;

import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.community.entity.Building;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.entity.HouseImage;
import com.community.residence.common.community.entity.HouseStatusHistory;
import com.community.residence.common.community.entity.PublicResource;
import com.community.residence.common.community.entity.ResourceTimeslot;
import com.community.residence.common.community.entity.Unit;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.mapper.HouseImageMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.common.community.mapper.PublicResourceMapper;
import com.community.residence.common.community.mapper.ResourceTimeslotMapper;
import com.community.residence.common.community.mapper.StructureBusinessCodeMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.common.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.reservation.entity.ResourceReservation;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.workorder.entity.ServiceCategory;
import com.community.residence.common.workorder.entity.StaffCommunity;
import com.community.residence.common.workorder.entity.StaffSchedule;
import com.community.residence.common.workorder.entity.StaffServiceCategory;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.entity.WorkOrderAssignment;
import com.community.residence.common.workorder.entity.WorkOrderAttachment;
import com.community.residence.common.workorder.entity.WorkOrderProcess;
import com.community.residence.common.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.common.workorder.mapper.StaffCommunityMapper;
import com.community.residence.common.workorder.mapper.StaffScheduleMapper;
import com.community.residence.common.workorder.mapper.StaffServiceCategoryMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.mapper.WorkOrderProcessMapper;
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

/** 非交易统计边界；staffTrend窗口按2026-10-12用户批准事件定义。 */
@ExtendWith(MockitoExtension.class)
class NontransactionStatisticsCoverageTest {
 @Mock CommunityMapper communityMapper; @Mock BuildingMapper buildingMapper; @Mock HouseMapper houseMapper;
 @Mock ResidentMapper residentMapper; @Mock WorkOrderMapper workOrderMapper; @Mock WorkOrderAssignmentMapper assignmentMapper; @Mock WorkOrderProcessMapper processMapper;
 @Mock ServiceCategoryMapper serviceCategoryMapper; @Mock SysUserMapper sysUserMapper; @Mock WorkOrderEvaluationMapper evaluationMapper;
 @Mock ResourceReservationMapper reservationMapper; @Mock LeaseRecordMapper leaseMapper;
 @Mock com.community.residence.common.resident.mapper.ResidenceRelationMapper residenceRelationMapper;
 @Mock com.community.residence.common.housing.mapper.HousingMapper housingMapper;
 @Mock com.community.residence.common.housing.mapper.ViewingAppointmentMapper viewingAppointmentMapper;
 @Mock com.community.residence.common.statistics.mapper.StatisticsSnapshotMapper statisticsSnapshotMapper;
 @InjectMocks StatisticsService service;
 @ParameterizedTest @CsvSource({"-4,1","1,1","7,7","90,90","999,90"})
 void emptyTrendClampsWindow(int input,int expected) {
  when(assignmentMapper.selectList(any())).thenReturn(List.of());
  when(processMapper.selectList(any())).thenReturn(List.of());
  try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)) {
   s.when(SecurityUtils::getUserId).thenReturn(7L);Map<String,Object> r=service.staffTrend(input);
   assertThat(r.get("days")).isEqualTo(expected);assertThat((Map<?,?>)r.get("assignedTrend")).hasSize(expected);
   for(String key:List.of("todayAssigned","todaySubmitted","totalSubmitted"))assertThat(r.get(key)).isEqualTo(0L);
   verifyNoInteractions(workOrderMapper);
  }
 }
 @Test void workorderDistributionReadsBackendValues() {
  WorkOrder a=new WorkOrder();a.setStatus("PENDING");a.setCategoryId(10L);a.setPriority("NORMAL");
  WorkOrder b=new WorkOrder();b.setStatus("CLOSED");b.setCategoryId(10L);b.setPriority("URGENT");when(workOrderMapper.selectList(any())).thenReturn(List.of(a,b));
  Map<String,Object> r=service.workOrderStatistics(3L);assertThat(r.get("total")).isEqualTo(2);
  assertThat(r.get("byStatus")).isEqualTo(Map.of("PENDING",1L,"CLOSED",1L));assertThat(r.get("byPriority")).isEqualTo(Map.of("NORMAL",1L,"URGENT",1L));
 }
 @Test void residentAndResourceDistributionsIncludeEmptyResult() {
  Resident a=new Resident();a.setId(1L);a.setStatus("ACTIVE");Resident b=new Resident();b.setId(2L);b.setStatus("FROZEN");
  ResidenceRelation relationA=new ResidenceRelation();relationA.setResidentId(1L);ResidenceRelation relationB=new ResidenceRelation();relationB.setResidentId(2L);
  when(residenceRelationMapper.selectList(any())).thenReturn(List.of(relationA, relationB));
  when(residentMapper.selectBatchIds(any())).thenReturn(List.of(a,b));
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
  stubDashboard();Map<String,Object> r=service.dashboard(null);
  assertThat(r.get("houseCount")).isEqualTo(0);assertThat(r.get("occupiedHouseCount")).isEqualTo(0L);assertThat((Map<?,?>)r.get("ratingDistribution")).isEmpty();assertThat(((Map<?,?>)r.get("workOrderTrend7d")).values()).allMatch(value -> value.equals(0L));
 }
 @Test void dashboardUsesCountsAndRows() {
   stubDashboard();
  WorkOrder o=new WorkOrder();o.setStatus("PENDING");o.setCreatedAt(LocalDate.now().atTime(10,0));when(workOrderMapper.selectList(any())).thenReturn(List.of(o));
   House occupied=new House();occupied.setStatus("TENANT_OCCUPIED");House h1=new House();h1.setStatus("VACANT");House h2=new House();h2.setStatus("VACANT");House h3=new House();h3.setStatus("VACANT");when(houseMapper.selectList(any())).thenReturn(List.of(occupied,h1,h2,h3));
  WorkOrderEvaluation e=new WorkOrderEvaluation();e.setRating(2);when(evaluationMapper.selectList(any())).thenReturn(List.of(e));Map<String,Object> r=service.dashboard(3L);
   assertThat(r.get("houseCount")).isEqualTo(4);assertThat(r.get("occupiedHouseCount")).isEqualTo(1L);assertThat(r.get("workOrderStatusDistribution")).isEqualTo(Map.of("PENDING",1L));
   assertThat(r.get("houseStatusDistribution")).isEqualTo(Map.of("VACANT",3L,"TENANT_OCCUPIED",1L));assertThat(r.get("ratingDistribution")).isEqualTo(Map.of(2,1L));
 }
 private void stubDashboard() {
  lenient().when(communityMapper.selectCount(any())).thenReturn(1L);lenient().when(buildingMapper.selectCount(any())).thenReturn(1L);
  lenient().when(houseMapper.selectList(any())).thenReturn(List.of());lenient().when(residentMapper.selectCount(any())).thenReturn(0L);
  lenient().when(leaseMapper.selectCount(any())).thenReturn(0L);lenient().when(workOrderMapper.selectCount(any())).thenReturn(0L);lenient().when(workOrderMapper.selectList(any())).thenReturn(List.of());
   lenient().when(reservationMapper.selectCount(any())).thenReturn(0L);lenient().when(evaluationMapper.selectCount(any())).thenReturn(0L);lenient().when(evaluationMapper.selectList(any())).thenReturn(List.of());
   lenient().when(residenceRelationMapper.selectList(any())).thenReturn(List.of());lenient().when(viewingAppointmentMapper.selectList(any())).thenReturn(List.of());
   lenient().when(statisticsSnapshotMapper.selectHousingViews(any(), any(), any())).thenReturn(List.of());
 }
}
