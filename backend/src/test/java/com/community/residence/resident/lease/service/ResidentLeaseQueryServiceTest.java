package com.community.residence.resident.lease.service;
import com.community.residence.common.lease.entity.*;
import com.community.residence.common.lease.mapper.*;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.lease.vo.LeaseVO;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.common.security.context.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.*;
import java.time.*;import java.math.BigDecimal;import java.util.*;
import static org.mockito.Mockito.*;import static org.assertj.core.api.Assertions.*;
class ResidentLeaseQueryServiceTest {
 static final LocalDate TODAY=LocalDate.of(2026,10,18);
 @AfterEach void clear(){SecurityUtils.clear();}
 LeaseVO lease(){var l=new LeaseVO();l.setId(4L);l.setStatus("ACTIVE");l.setStartDate(TODAY);l.setEndDate(TODAY.plusMonths(12).minusDays(1));return l;}
 LeaseContractTerm term(){var t=new LeaseContractTerm();t.setId(8L);t.setLeaseId(4L);t.setTermType("INITIAL");t.setStatus("ACTIVE");t.setContractMonths(12);t.setMonthlyRent(BigDecimal.TEN);t.setStartDate(TODAY);t.setEndDate(TODAY.plusMonths(12).minusDays(1));return t;}
 LeasePayment first(){var p=new LeasePayment();p.setId(2L);p.setLeaseId(4L);p.setPaymentType("FIRST");p.setStatus("SUCCESS");p.setMonths(3);p.setAmount(BigDecimal.valueOf(60));p.setPeriodStart(TODAY);p.setPeriodEnd(TODAY.plusMonths(3).minusDays(1));return p;}
 @Test void firstThreeMonthsDoNotMeanFullContractPaidOrExtended(){var l=lease();var s=ResidentLeaseQueryService.summarize(l,List.of(term()),List.of(first()),TODAY);assertThat(s.paidRentMonths()).isEqualTo(3);assertThat(s.unpaidRentMonths()).isEqualTo(9);assertThat(s.firstPaymentPaidAmount()).isEqualByComparingTo("60");assertThat(s.actions().get("firstPayment").enabled()).isFalse();assertThat(s.actions().get("rentPayment").enabled()).isTrue();assertThat(l.getEndDate()).isEqualTo(TODAY.plusMonths(12).minusDays(1));}
 @Test void pendingAndAbnormalSuccessDoNotCountAsPaid(){var p=first();p.setStatus("PENDING");var s=ResidentLeaseQueryService.summarize(lease(),List.of(term()),List.of(p),TODAY);assertThat(s.paidRentMonths()).isZero();assertThat(s.firstPaymentSucceeded()).isFalse();p.setStatus("SUCCESS");p.setSettlementIssue("LATE_PAYMENT");s=ResidentLeaseQueryService.summarize(lease(),List.of(term()),List.of(p),TODAY);assertThat(s.paidRentMonths()).isZero();assertThat(s.firstPaymentSucceeded()).isFalse();}
 @Test void missingCoverageDisablesPaymentWithReasonInsteadOfGuessing(){var p=first();p.setPeriodStart(null);var s=ResidentLeaseQueryService.summarize(lease(),List.of(term()),List.of(p),TODAY);assertThat(s.unpaidRentMonths()).isNull();assertThat(s.actions().get("rentPayment").enabled()).isFalse();assertThat(s.actions().get("rentPayment").reason()).contains("覆盖快照");assertThat(s.firstPaymentSucceeded()).isTrue();}
 @Test void firstStartDatePassedCannotPayAndSignedDateIsNotActive(){var l=lease();l.setStatus("TO_SIGN");l.setAgreementStatus("SIGNED");l.setStartDate(TODAY.minusDays(1));var t=term();t.setStatus("SIGNED");var s=ResidentLeaseQueryService.summarize(l,List.of(t),List.of(),TODAY);assertThat(s.displayStatus()).isEqualTo("待支付首期费用");assertThat(s.actions().get("firstPayment").enabled()).isFalse();assertThat(s.actions().get("moveOut").enabled()).isFalse();}
 @Test void identityRejectedBeforeAnyMapperAccess(){var a=mock(LeaseRecordMapper.class);var b=mock(LeaseContractTermMapper.class);var c=mock(LeasePaymentMapper.class);var d=mock(LeaseService.class);SecurityUtils.setAuthentication(new UserContext(9L,"staff","STAFF",Set.of()));assertThatThrownBy(()->new ResidentLeaseQueryService(a,b,c,d).page(1,10)).isInstanceOf(com.community.residence.common.web.exception.ForbiddenException.class);verifyNoInteractions(a,b,c,d);}
 @Test void pageReadsTermsAndPaymentsOnceForWholePage() {
  for(var type:List.of(LeaseRecord.class,LeaseContractTerm.class,LeasePayment.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),""),type);
  var a=mock(LeaseRecordMapper.class);var b=mock(LeaseContractTermMapper.class);var c=mock(LeasePaymentMapper.class);var d=mock(LeaseService.class);
  SecurityUtils.setAuthentication(new UserContext(9L,"resident","RESIDENT",Set.of()));
  var row=new LeaseRecord();row.setId(4L);var page=new Page<LeaseRecord>(1,10,1);page.setRecords(List.of(row));
  when(a.selectPage(any(),any())).thenAnswer(invocation->{LambdaQueryWrapper<LeaseRecord> q=invocation.getArgument(1);assertThat(q.getParamNameValuePairs()).isEmpty();assertThat(q.getSqlSegment()).contains("tenant_id");assertThat(q.getParamNameValuePairs()).containsValue(9L);return page;});
  when(b.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(term()));when(c.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first()));when(d.batchViews(List.of(row))).thenReturn(List.of(lease()));
  assertThat(new ResidentLeaseQueryService(a,b,c,d).page(1,10).getRecords()).hasSize(1);
  verify(b,times(1)).selectList(any(LambdaQueryWrapper.class));verify(c,times(1)).selectList(any(LambdaQueryWrapper.class));verifyNoMoreInteractions(b,c);
 }
}
