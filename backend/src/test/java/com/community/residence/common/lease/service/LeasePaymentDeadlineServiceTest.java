package com.community.residence.common.lease.service;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
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
    @Mock com.community.residence.common.community.mapper.HouseMapper houses;
    @Mock com.community.residence.common.community.service.HouseStateService houseStateService;
    @Mock com.community.residence.common.resident.mapper.ResidenceRelationMapper relations;
    @InjectMocks LeasePaymentDeadlineService service;
    LeaseRecord lease;
    LocalDateTime now=LocalDateTime.of(2026,10,16,0,0,30);
    @BeforeAll static void metadata(){
        for(Class<?> c:List.of(LeasePayment.class,com.community.residence.common.resident.entity.ResidenceApplication.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),""),c);
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
    @Test void thirtySecondBufferIsRespected(){
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
        var house=new com.community.residence.common.community.entity.House();
        house.setId(50L);
        house.setStatus("VACANT");
        doAnswer(invocation -> {
            house.setStatus("TENANT_OCCUPIED");
            return null;
        }).when(houseStateService).refresh(eq(50L), anyString());
        service.beginResidence(10L,now);
        assertThat(house.getStatus()).isEqualTo("TENANT_OCCUPIED");
        verify(houseStateService).refresh(eq(50L), anyString());
    }
    @Test void endedRelationCannotBeReoccupiedByDateTask() {
        lease.setStatus("ACTIVE");
        lease.setStartDate(now.toLocalDate());
        when(relations.selectCount(any())).thenReturn(0L);
        service.beginResidence(10L,now);
        verifyNoInteractions(houses);
    }
    @Test void firstOrderCrossingMidnightKeepsItsFifteenMinuteWindowAndBuffer() {
        var created=LocalDateTime.of(2026,10,15,23,55);
        var p=new LeasePayment();p.setId(1L);p.setStatus("PENDING");
        p.setChannelExpiresAt(created.plusMinutes(15));p.setExpiresAt(created.plusMinutes(15).plusSeconds(30));
        when(payments.selectList(any())).thenReturn(List.of(p));when(payments.selectByIdForUpdate(1L)).thenReturn(p);
        assertThat(service.rejectUnpaid(10L,created.plusMinutes(15).plusSeconds(29))).isFalse();
        verifyNoInteractions(applications);
        assertThat(service.rejectUnpaid(10L,created.plusMinutes(15).plusSeconds(30))).isTrue();
        assertThat(p.getStatus()).isEqualTo("CLOSED");
    }
}
