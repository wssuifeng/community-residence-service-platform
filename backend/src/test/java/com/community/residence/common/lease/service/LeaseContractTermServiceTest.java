package com.community.residence.common.lease.service;
import com.community.residence.common.lease.entity.LeaseChangeLog;
import com.community.residence.common.lease.entity.LeaseContractTerm;
import com.community.residence.common.lease.entity.LeaseDepositRefund;
import com.community.residence.common.lease.entity.LeaseExtensionApplication;
import com.community.residence.common.lease.entity.LeaseMoveOutApplication;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.entity.LeaseReminder;
import com.community.residence.common.lease.mapper.LeaseChangeLogMapper;
import com.community.residence.common.lease.mapper.LeaseContractTermMapper;
import com.community.residence.common.lease.mapper.LeaseDepositRefundMapper;
import com.community.residence.common.lease.mapper.LeaseExtensionApplicationMapper;
import com.community.residence.common.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.mapper.LeaseReminderMapper;
import com.community.residence.common.lease.dto.RenewLeaseDTO;
import com.community.residence.common.security.context.SecurityUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness=Strictness.LENIENT)
class LeaseContractTermServiceTest {
    @Mock LeaseContractTermMapper terms;
    @Mock LeaseRecordMapper leases;
    @Mock com.community.residence.common.payment.mapper.LeasePaymentMapper payments;
    @Mock LeaseChangeLogService changeLog;
    @InjectMocks LeaseContractTermService service;
    LeaseRecord lease;
    LeaseContractTerm term;
    MockedStatic<SecurityUtils> security;
    @BeforeAll static void metadata(){
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),""),LeaseContractTerm.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),""),com.community.residence.common.payment.entity.LeasePayment.class);
    }
    @BeforeEach void setup(){
        security=mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(1L);
        lease=new LeaseRecord();
        lease.setId(10L);
        lease.setCommunityId(2L);
        lease.setTenantId(1L);
        lease.setStatus("ACTIVE");
        lease.setStartDate(LocalDate.now());
        lease.setEndDate(LeaseTermRules.end(lease.getStartDate(),12));
        lease.setContractMonths(12);
        lease.setFirstPaymentMonths(3);
        lease.setPaymentCycleMonths(1);
        lease.setMonthlyRent(new BigDecimal("3000"));
        lease.setDeposit(new BigDecimal("3000"));
        when(leases.selectByIdForUpdate(10L)).thenReturn(lease);
        when(leases.selectById(10L)).thenReturn(lease);
        when(terms.selectList(any())).thenReturn(List.of());
        term=new LeaseContractTerm();
        term.setId(20L);
        term.setLeaseId(10L);
        term.setTermType("RENEWAL");
        term.setStartDate(lease.getEndDate().plusDays(1));
        term.setEndDate(LeaseTermRules.end(term.getStartDate(),6));
        term.setStatus("OFFERED");
        when(terms.lock(20L)).thenReturn(term);
    }
    @AfterEach void close(){
        security.close();
    }
    RenewLeaseDTO dto(){
        var d=new RenewLeaseDTO();
        d.setMonths(6);
        d.setFirstPaymentMonths(1);
        d.setPaymentCycleMonths(1);
        d.setMonthlyRent(new BigDecimal("3100"));
        d.setDeposit(new BigDecimal("3000"));
        return d;
    }
    @Test void proposalDoesNotExtendOrReplaceOldPrice(){
        var end=lease.getEndDate();
        var result=service.propose(10L,dto());
        assertThat(result.getContractMonths()).isEqualTo(6);
        assertThat(result.getFirstPaymentMonths()).isEqualTo(1);
        assertThat(result.getMonthlyRent()).isEqualByComparingTo("3100");
        assertThat(result.getDeposit()).isZero();
        assertThat(result.getStatus()).isEqualTo("OFFERED");
        assertThat(result.getContentSnapshot()).contains("不构成可靠电子签名");
        assertThat(lease.getEndDate()).isEqualTo(end);
        assertThat(lease.getMonthlyRent()).isEqualByComparingTo("3000");
        verify(leases,never()).updateById(any(LeaseRecord.class));
    }
    @Test void residentConfirmationDoesNotExtend(){
        var end=lease.getEndDate();
        service.confirm(10L,20L);
        assertThat(term.getStatus()).isEqualTo("SIGNED");
        assertThat(term.getConfirmedAt()).isNotNull();
        assertThat(lease.getEndDate()).isEqualTo(end);
    }
    @Test void otherResidentCannotConfirm(){
        security.when(SecurityUtils::getUserId).thenReturn(9L);
        assertThatThrownBy(()->service.confirm(10L,20L)).isInstanceOf(RuntimeException.class);
        verify(terms,never()).updateById(any(LeaseContractTerm.class));
    }
    @Test void duplicateConfirmationKeepsFirstTimestamp(){
        term.setStatus("SIGNED");
        term.setConfirmedAt(LocalDateTime.of(2026,10,1,12,0));
        service.confirm(10L,20L);
        assertThat(term.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026,10,1,12,0));
        verify(terms,never()).updateById(any(LeaseContractTerm.class));
    }
    @Test void activeRenewalExtendsSixMonthsDespiteOneMonthFirstPayment(){
        term.setStatus("SIGNED");
        term.setFirstPaymentMonths(1);
        service.activate(20L,lease);
        assertThat(lease.getEndDate()).isEqualTo(term.getEndDate());
        assertThat(term.getStatus()).isEqualTo("ACTIVE");
        assertThat(lease.getMonthlyRent()).isEqualByComparingTo("3000");
    }
    @Test void cancelledRenewalCannotActivate(){
        term.setStatus("CANCELLED");
        assertThatThrownBy(()->service.activate(20L,lease)).isInstanceOf(RuntimeException.class);
        verify(leases,never()).updateById(any(LeaseRecord.class));
    }
    @Test void nonContiguousRenewalCannotActivate(){
        term.setStatus("SIGNED");
        term.setStartDate(term.getStartDate().plusDays(1));
        assertThatThrownBy(()->service.activate(20L,lease)).isInstanceOf(RuntimeException.class);
    }
    @Test void cancellingProposalDoesNotCancelOriginalContract(){
        var end=lease.getEndDate();
        service.cancel(10L,20L);
        assertThat(term.getStatus()).isEqualTo("CANCELLED");
        assertThat(lease.getStatus()).isEqualTo("ACTIVE");
        assertThat(lease.getEndDate()).isEqualTo(end);
    }
    @Test void existingOpenRenewalPreventsDuplicate(){
        when(terms.selectList(any())).thenReturn(List.of(term));
        assertThatThrownBy(()->service.propose(10L,dto())).isInstanceOf(RuntimeException.class).hasMessageContaining("已有未生效");
    }
    @Test void pastEndCannotSkipGap(){
        lease.setEndDate(LocalDate.now().minusDays(1));
        assertThatThrownBy(()->service.propose(10L,dto())).isInstanceOf(RuntimeException.class).hasMessageContaining("断档");
    }
    @Test void legacyUnknownScheduleCannotBeInvented(){
        lease.setContractMonths(null);
        assertThatThrownBy(()->service.initial(lease)).isInstanceOf(RuntimeException.class).hasMessageContaining("不能猜测");
    }
    @Test void initialSnapshotRetainsSeparateTotals(){
        var initial=service.initial(lease);
        assertThat(initial.getContractMonths()).isEqualTo(12);
        assertThat(initial.getFirstPaymentMonths()).isEqualTo(3);
        assertThat(initial.getContentSnapshot()).contains("首付：3个月");
    }
    @Test void changedDepositNotSilentlyCollectedOnRenewal(){
        var d=dto();
        d.setDeposit(new BigDecimal("6000"));
        assertThatThrownBy(()->service.propose(10L,d)).isInstanceOf(RuntimeException.class).hasMessageContaining("差额");
    }
    @Test void firstPaymentCannotSelectAnActiveRenewalTerm(){
        term.setStatus("ACTIVE");
        when(terms.selectList(any())).thenReturn(List.of(term));
        assertThatThrownBy(()->service.requirePayable(10L,"FIRST")).hasMessageContaining("没有符合");
    }
    @Test void settlementPurposeMustMatchContractType(){
        term.setStatus("SIGNED");
        assertThat(service.canSettle(20L,10L,"FIRST")).isFalse();
        assertThat(service.canSettle(20L,10L,"RENEWAL")).isTrue();
    }
    @Test void unknownPurposeNeverFallsBackToRent(){
        assertThatThrownBy(()->service.requirePayable(10L,"OTHER")).hasMessageContaining("未知支付用途");
    }
    @Test void billingSummaryKeepsEachContractAndPaidIntervalSeparate(){
        term.setStatus("ACTIVE");
        term.setContractMonths(6);
        term.setStartDate(LocalDate.now().minusMonths(2));
        term.setEndDate(term.getStartDate().plusMonths(6).minusDays(1));term.setMonthlyRent(new BigDecimal("3000"));
        var p=new com.community.residence.common.payment.entity.LeasePayment();
        p.setTermId(20L);p.setStatus("SUCCESS");
        p.setMonths(1);
        p.setPeriodStart(term.getStartDate());
        p.setPeriodEnd(LeaseTermRules.end(term.getStartDate(),1));
        p.setPaymentNo("MOCK-COVERAGE");
        when(terms.selectList(any())).thenReturn(List.of(term));
        when(payments.selectList(any())).thenReturn(List.of(p));
        var summary=service.list(10L).get(0);
        assertThat(summary.getPaidMonths()).isEqualTo(1);
        assertThat(summary.getRemainingMonths()).isEqualTo(5);
        assertThat(summary.isOverdue()).isTrue();
        assertThat(summary.getPaidPeriods()).hasSize(1);
    }
}
