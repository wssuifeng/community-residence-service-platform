package com.community.residence.lease.service;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness=Strictness.LENIENT)
class LeasePaymentDeadlineServiceTest {
    @Mock LeaseService leaseService;
    @Mock LeaseRecordMapper leases;
    @Mock LeasePaymentMapper payments;
    @Mock ResidenceApplicationMapper applications;
    @Mock com.community.residence.community.mapper.HouseMapper houses;
    @Mock com.community.residence.resident.mapper.ResidenceRelationMapper relations;
    @InjectMocks LeasePaymentDeadlineService service;
    LeaseRecord lease;
    LocalDateTime now=LocalDateTime.of(2026,10,16,0,5);
    @BeforeAll static void metadata(){
        for(Class<?> c:List.of(LeasePayment.class,com.community.residence.resident.entity.ResidenceApplication.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),""),c);
    }
    @BeforeEach void setup(){
        lease=new LeaseRecord();
        lease.setId(10L);
        lease.setStartDate(now.toLocalDate().minusDays(1));
        lease.setStatus("TO_SIGN");
        when(leaseService.lockForSettlement(10L)).thenReturn(lease);
        when(payments.selectList(any())).thenReturn(List.of());
    }
    @Test void yesterdayUnpaidIsAutomaticallyRejected(){
        assertThat(service.rejectUnpaid(10L,now)).isTrue();
        verify(leaseService).applyTransition(eq(lease),eq("REJECTED"),contains("自动驳回"));
        verify(applications).update(isNull(),any());
    }
    @Test void startDayStillPayable(){
        lease.setStartDate(now.toLocalDate());
        assertThat(service.rejectUnpaid(10L,now)).isFalse();
        verifyNoInteractions(applications);
    }
    @Test void fiveMinuteBufferIsRespected(){
        assertThat(service.rejectUnpaid(10L,now.minusSeconds(1))).isFalse();
        verifyNoInteractions(applications);
    }
    @Test void confirmedPaymentNeverRejected(){
        var p=new LeasePayment();
        p.setId(1L);
        p.setStatus("SUCCESS");
        when(payments.selectList(any())).thenReturn(List.of(p));
        when(payments.selectByIdForUpdate(1L)).thenReturn(p);
        assertThat(service.rejectUnpaid(10L,now)).isFalse();
        verifyNoInteractions(applications);
    }
    @Test void activeLeaseNeverRejected(){
        lease.setStatus("ACTIVE");
        assertThat(service.rejectUnpaid(10L,now)).isFalse();
        verifyNoInteractions(applications);
    }
    @Test void lateSuccessIssueRequiresReviewNotAutomaticRelease(){
        var p=new LeasePayment();
        p.setId(1L);
        p.setStatus("CLOSED");
        p.setSettlementIssue("LATE_SUCCESS");
        when(payments.selectList(any())).thenReturn(List.of(p));
        when(payments.selectByIdForUpdate(1L)).thenReturn(p);
        assertThat(service.rejectUnpaid(10L,now)).isFalse();
    }
    @Test void pendingLocalDeadlineIsNotPrematurelyClosed(){
        var p=new LeasePayment();
        p.setId(1L);
        p.setStatus("PENDING");
        p.setExpiresAt(now.plusSeconds(1));
        when(payments.selectByIdForUpdate(1L)).thenReturn(p);
        service.closeExpired(1L,now);
        verify(payments,never()).updateById(any(LeasePayment.class));
    }
    @Test void deadlineClosesOnlyPendingOrder(){
        var p=new LeasePayment();
        p.setId(1L);
        p.setStatus("PENDING");
        p.setExpiresAt(now);
        when(payments.selectByIdForUpdate(1L)).thenReturn(p);
        service.closeExpired(1L,now);
        assertThat(p.getStatus()).isEqualTo("CLOSED");
    }
    @Test void futureStartDoesNotMarkHouseOccupied() {
        lease.setStatus("ACTIVE");
        lease.setStartDate(now.toLocalDate().plusDays(1));
        service.beginResidence(10L,now);
        verifyNoInteractions(houses,relations);
    }
    @Test void paidReservationBecomesOccupiedOnlyWhenDateArrivesAndRelationRemains() {
        lease.setStatus("ACTIVE");
        lease.setHouseId(50L);
        lease.setStartDate(now.toLocalDate());
        when(relations.selectCount(any())).thenReturn(1L);
        var house=new com.community.residence.community.entity.House();
        house.setId(50L);
        house.setStatus("RESERVED");
        when(houses.selectById(50L)).thenReturn(house);
        service.beginResidence(10L,now);
        assertThat(house.getStatus()).isEqualTo("OCCUPIED");
        verify(houses).updateById(house);
    }
    @Test void endedRelationCannotBeReoccupiedByDateTask() {
        lease.setStatus("ACTIVE");
        lease.setStartDate(now.toLocalDate());
        when(relations.selectCount(any())).thenReturn(0L);
        service.beginResidence(10L,now);
        verifyNoInteractions(houses);
    }
}
