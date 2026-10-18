package com.community.residence.staff.workorder.service;
import com.community.residence.common.security.context.*;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.service.WorkOrderService;
import com.community.residence.common.workorder.vo.WorkOrderLoadVO;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class StaffWorkbenchServiceTest {
 private final WorkOrderMapper mapper=mock(WorkOrderMapper.class);
 private final WorkOrderService orders=mock(WorkOrderService.class);
 private final StaffWorkbenchService service=new StaffWorkbenchService(mapper,orders);
 @AfterEach void clear(){SecurityUtils.clear();}
 @Test void usesOnlyCurrentStaffAndDoesNotTurnCumulativeCompletionIntoToday(){
  SecurityUtils.setAuthentication(new UserContext(23L,"staff","STAFF",Set.of()));
  var load=new WorkOrderLoadVO();load.setTodoCount(3);load.setDoingCount(4);load.setCompletedCount(12);load.setUrgentCount(1);
  when(mapper.selectCurrentLoad(23L)).thenReturn(load);when(orders.currentStaffTodos(23L)).thenReturn(List.of());
  var result=service.summary();assertThat(result.getCompletedCount()).isEqualTo(12);assertThat(result.getDoingCount()).isEqualTo(4);
  verify(mapper).selectCurrentLoad(23L);verify(orders).currentStaffTodos(23L);verifyNoMoreInteractions(mapper,orders);
 }
 @Test void sameNumericIdInResidentSpaceCannotReadStaffWorkbench(){
  SecurityUtils.setAuthentication(new UserContext(23L,"resident","RESIDENT",Set.of()));
  assertThatThrownBy(service::summary).isInstanceOf(com.community.residence.common.web.exception.BusinessException.class);
  verifyNoInteractions(mapper,orders);
 }
 @Test void mapperBoundsTodosAndUsesLatestAssignmentForCountsAndRows() throws Exception {
  for(String method:List.of("selectCurrentLoad","selectCurrentTodos")) {
   String sql=String.join(" ",WorkOrderMapper.class.getMethod(method,Long.class).getAnnotation(org.apache.ibatis.annotations.Select.class).value());
   assertThat(sql).contains("a.assignee_id = #{staffId}","MAX(b.id)","b.work_order_id = w.id").doesNotContain("CURRENT_DATE");
   if(method.equals("selectCurrentTodos")) assertThat(sql).contains("PARTITION BY w.status","rn <= 5");
  }
 }
}
