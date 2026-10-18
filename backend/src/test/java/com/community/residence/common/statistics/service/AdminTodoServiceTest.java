package com.community.residence.common.statistics.service;
import com.community.residence.common.security.context.IdentityPrincipal;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.feedback.mapper.FeedbackMapper;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
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
