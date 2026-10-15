package com.community.residence.statistics.service;
import com.community.residence.common.context.*;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.feedback.mapper.FeedbackMapper;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AdminTodoServiceTest {
 private final WorkOrderMapper orders=mock(WorkOrderMapper.class);
 private final AdminTodoService service=new AdminTodoService(orders,mock(FeedbackMapper.class),mock(ResourceReservationMapper.class),mock(ResidenceApplicationMapper.class),mock(LeaseMoveOutApplicationMapper.class),mock(ViewingAppointmentMapper.class),mock(LeaseRecordMapper.class));
 @AfterEach void after(){SecurityContextHolder.clearContext();}
 @Test void residentCannotReadAdminTodoNumbers(){SecurityUtils.setAuthentication(new UserContext(1L,"测试","RESIDENT",Set.of()));assertThatThrownBy(service::summary).isInstanceOf(ForbiddenException.class);verifyNoInteractions(orders);}
 @Test void adminSummaryUsesActualMapperCounts(){SecurityUtils.setAuthentication(new UserContext(1L,"测试","ADMIN",Set.of(8L)));when(orders.selectCount(any())).thenReturn(3L);var result=service.summary();assertThat(result).containsEntry("workOrders",3L);assertThat(result.keySet()).containsExactly("workOrders","feedbacks","reservations","applications","moveout","housings","leases");}
}
