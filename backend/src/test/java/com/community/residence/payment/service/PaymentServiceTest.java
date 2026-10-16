package com.community.residence.payment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.*;
import com.community.residence.lease.entity.*;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.service.*;
import com.community.residence.payment.config.PaymentProperties;
import com.community.residence.payment.dto.*;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.payment.service.channel.*;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.messaging.service.NotificationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** 旧“付款月数直接延租/当前月租反推历史”的断言由已采纳23合同段规则替代，所有渠道均mock。 */
@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness=Strictness.LENIENT)
class PaymentServiceTest {
 @Mock LeasePaymentMapper payments; @Mock LeaseRecordMapper leases;
 @Mock SysAdminCommunityMapper admins; @Mock NotificationService notifications;
 @Mock PaymentProperties properties; @Mock PaymentChannelClient alipay; @Mock PaymentChannelClient wechat;
 @Mock RedissonClient redisson; @Mock RLock lock;
 @Mock PlatformTransactionManager tx; @Mock LeaseService leaseService; @Mock LeaseContractTermService terms;
 PaymentService service; LeaseRecord lease; LeaseContractTerm term; LeasePayment order;
 MockedStatic<SecurityUtils> security;
 @BeforeAll static void tableInfo(){for(Class<?> type:List.of(LeasePayment.class,LeaseContractTerm.class))TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),type);}
 @BeforeEach void setup() throws Exception {
  security=mockStatic(SecurityUtils.class);security.when(SecurityUtils::getUserId).thenReturn(1L);security.when(()->SecurityUtils.hasRole(anyString())).thenReturn(true);
  service=new PaymentService(payments,leases,admins,notifications,properties,alipay,wechat);service.setRedissonClient(redisson);service.setSettlementInfrastructure(tx,leaseService);ReflectionTestUtils.setField(service,"termService",terms);
  when(redisson.getLock(anyString())).thenReturn(lock);when(lock.tryLock(anyLong(),any(TimeUnit.class))).thenReturn(true);when(tx.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
  when(alipay.channel()).thenReturn("ALIPAY");when(wechat.channel()).thenReturn("WECHAT");when(alipay.enabled()).thenReturn(true);when(wechat.enabled()).thenReturn(true);when(alipay.createPayment(any(),anyString(),any())).thenReturn("<form></form>");when(wechat.createPayment(any(),anyString(),any())).thenReturn("weixin://test");
  lease=new LeaseRecord();lease.setId(100L);lease.setTenantId(1L);lease.setCommunityId(2L);lease.setHouseId(3L);lease.setStatus("ACTIVE");lease.setAgreementStatus("SIGNED");lease.setStartDate(LocalDate.now());lease.setEndDate(LeaseTermRules.end(lease.getStartDate(),12));lease.setMonthlyRent(new BigDecimal("3000"));lease.setDeposit(new BigDecimal("3000"));
  when(leases.selectById(100L)).thenReturn(lease);when(leaseService.lockForSettlement(100L)).thenReturn(lease);
  term=new LeaseContractTerm();term.setId(7L);term.setLeaseId(100L);term.setTermType("INITIAL");term.setStatus("ACTIVE");term.setStartDate(lease.getStartDate());term.setEndDate(lease.getEndDate());term.setContractMonths(12);term.setFirstPaymentMonths(3);term.setPaymentCycleMonths(1);term.setMonthlyRent(new BigDecimal("3000"));term.setDeposit(new BigDecimal("3000"));
  when(terms.requirePayable(eq(100L),anyString())).thenReturn(term);when(terms.rows(100L)).thenReturn(List.of(term));when(terms.canSettle(anyLong(),anyLong(),anyString())).thenReturn(true);
  when(payments.selectList(any())).thenReturn(List.of());when(payments.insert(any(LeasePayment.class))).thenAnswer(i->{var p=i.getArgument(0,LeasePayment.class);p.setId(10L);order=p;return 1;});
  when(payments.selectById(anyLong())).thenAnswer(i->order);when(payments.selectByIdForUpdate(anyLong())).thenAnswer(i->order);when(payments.selectOne(any())).thenAnswer(i->order);when(admins.selectList(any())).thenReturn(List.of());
 }
 @AfterEach void close(){security.close();}
 CreateRenewPaymentDTO dto(String channel){var d=new CreateRenewPaymentDTO();d.setChannel(channel);d.setMonths(1);d.setQuoteVersion(RentSchedule.quote(RentSchedule.build(List.of(term),List.of()),1).version());return d;}
 CreateRenewPaymentDTO legacyDto(){var d=new CreateRenewPaymentDTO();d.setChannel("ALIPAY");d.setMonths(1);return d;}
 void first(){lease.setStatus("TO_SIGN");term.setStatus("SIGNED");}
 LeasePayment pending(String type){var p=new LeasePayment();p.setId(10L);p.setPaymentNo("TEST-ONLY");p.setLeaseId(100L);p.setResidentId(1L);p.setTermId(7L);p.setStatus("PENDING");p.setChannel("ALIPAY");p.setPaymentType(type);p.setMonths(3);p.setAmount(new BigDecimal("9000"));p.setRentAmount(new BigDecimal("9000"));p.setDepositAmount(BigDecimal.ZERO);p.setPeriodStart(term.getStartDate());p.setPeriodEnd(LeaseTermRules.end(term.getStartDate(),3));order=p;return p;}
 ChannelTradeResult success(){return new ChannelTradeResult("MOCK-TRADE",LocalDateTime.now());}
 @Test void firstThreeMonthsQuoteIsRentPlusOneDeposit(){first();var d=new CreateFirstPaymentDTO();d.setChannel("ALIPAY");var result=service.createFirstPayment(100L,d);assertThat(result.getAmount()).isEqualByComparingTo("12000");assertThat(result.getRentAmount()).isEqualByComparingTo("9000");assertThat(result.getDepositAmount()).isEqualByComparingTo("3000");assertThat(result.getPeriodEnd()).isEqualTo(LeaseTermRules.end(lease.getStartDate(),3));assertThat(result.getMonths()).isEqualTo(3);}
 @Test void firstPaymentCannotBeMadeForAnotherResident(){security.when(SecurityUtils::getUserId).thenReturn(9L);assertThatThrownBy(()->service.createFirstPayment(100L,new CreateFirstPaymentDTO())).isInstanceOf(ForbiddenException.class);verify(payments,never()).insert(any(LeasePayment.class));}
 @Test void missingLeaseRejected(){assertThatThrownBy(()->service.createRentPayment(999L,dto("ALIPAY"))).isInstanceOf(ResourceNotFoundException.class);}
 @Test void unconfirmedFirstRejected(){lease.setStatus("TO_SIGN");lease.setAgreementStatus("PARTIAL");var d=new CreateFirstPaymentDTO();d.setChannel("ALIPAY");assertThatThrownBy(()->service.createFirstPayment(100L,d)).isInstanceOf(BusinessException.class);}
 @Test void pastStartFirstRejectedRatherThanMovingStart(){first();lease.setStartDate(LocalDate.now().minusDays(1));var d=new CreateFirstPaymentDTO();d.setChannel("ALIPAY");assertThatThrownBy(()->service.createFirstPayment(100L,d)).isInstanceOf(BusinessException.class).hasMessageContaining("计划起租日");}
 @Test void channelDisabledNeverCreatesOrder(){when(alipay.enabled()).thenReturn(false);assertThatThrownBy(()->service.createRentPayment(100L,dto("ALIPAY"))).isInstanceOf(BusinessException.class);verify(payments,never()).insert(any(LeasePayment.class));}
 @Test void leaseNotActiveCannotPayOrdinaryRent(){lease.setStatus("MOVED_OUT");assertThatThrownBy(()->service.createRentPayment(100L,dto("ALIPAY"))).isInstanceOf(BusinessException.class);}
 @Test void lockFailureDoesNotCreateOrder() throws Exception{when(lock.tryLock(anyLong(),any())).thenReturn(false);assertThatThrownBy(()->service.createRentPayment(100L,dto("ALIPAY"))).isInstanceOf(BusinessException.class);verify(payments,never()).insert(any(LeasePayment.class));}
 @Test void lockReleasedAfterSuccessfulCreate(){service.createRentPayment(100L,dto("ALIPAY"));verify(lock).unlock();}
 @Test void outOfContractMonthsAreRejectedNotSilentlyChanged(){var d=dto("ALIPAY");d.setMonths(99);assertThatThrownBy(()->service.createRentPayment(100L,d)).hasMessageContaining("1至12");verify(payments,never()).insert(any(LeasePayment.class));}
 @Test void selectedFourMonthsAreChargedExactlyWithoutDeposit(){var d=dto("ALIPAY");d.setMonths(4);d.setQuoteVersion(service.quote(100L,"RENT",4).getQuoteVersion());var result=service.createRentPayment(100L,d);assertThat(result.getMonths()).isEqualTo(4);assertThat(result.getAmount()).isEqualByComparingTo("12000");assertThat(result.getDepositAmount()).isZero();assertThat(result.getCoverageDetails()).contains("monthlyRent");}
 @Test void missingOrChangedQuoteRejectedBeforeOrderInsert(){var d=dto("ALIPAY");d.setQuoteVersion("stale");assertThatThrownBy(()->service.createRentPayment(100L,d)).hasMessageContaining("报价已变化");verify(payments,never()).insert(any(LeasePayment.class));}
 @Test void tailPeriodDoesNotOvercharge(){term.setContractMonths(1);term.setEndDate(term.getStartDate().plusMonths(1).minusDays(1));term.setPaymentCycleMonths(3);var result=service.createRentPayment(100L,dto("ALIPAY"));assertThat(result.getMonths()).isEqualTo(1);}
 @Test void pendingIsResumedWithoutDuplicateInsert(){var p=pending("RENT");when(payments.selectList(any())).thenReturn(List.of(p));var result=service.createRentPayment(100L,dto("ALIPAY"));assertThat(result.getPaymentNo()).isEqualTo("TEST-ONLY");verify(payments,never()).insert(any(LeasePayment.class));}
 @Test void credentialFailureKeepsRecoverablePending(){when(alipay.createPayment(any(),anyString(),any())).thenThrow(new IllegalStateException("mock transport"));var result=service.createRentPayment(100L,dto("ALIPAY"));assertThat(result.getStatus()).isEqualTo("PENDING");assertThat(result.getAlipayForm()).isNull();}
 @Test void wechatCredentialNotAlipayForm(){var result=service.createRentPayment(100L,dto("WECHAT"));assertThat(result.getQrCode()).isEqualTo("weixin://test");assertThat(result.getAlipayForm()).isNull();}
 @Test void localExpiryIsThirtySecondsAfterChannelDeadline(){service.createRentPayment(100L,dto("ALIPAY"));assertThat(order.getExpiresAt()).isEqualTo(order.getChannelExpiresAt().plusSeconds(30));}
 @Test void quoteDoesNotCreateOrCallChannel(){var result=service.quote(100L,"RENT");assertThat(result.getAmount()).isEqualByComparingTo("3000");verify(payments,never()).insert(any(LeasePayment.class));verify(alipay,never()).createPayment(any(),anyString(),any());verify(alipay,never()).queryTrade(anyString());}
 @Test void firstSuccessActivatesOnceWithoutExtending(){first();var p=pending("FIRST");p.setDepositAmount(new BigDecimal("3000"));var end=lease.getEndDate();service.settleSuccess(p,success());assertThat(p.getStatus()).isEqualTo("SUCCESS");verify(terms).activate(7L,lease);verify(leaseService).applyTransition(eq(lease),eq("ACTIVE"),anyString());assertThat(lease.getEndDate()).isEqualTo(end);}
 @Test void duplicateSuccessNeverActivatesAgain(){var p=pending("RENEWAL");p.setStatus("SUCCESS");service.settleSuccess(p,success());verify(terms,never()).activate(anyLong(),any());}
 @Test void ordinaryRentSettlementNeverExtends(){var p=pending("RENT");var end=lease.getEndDate();service.settleSuccess(p,success());assertThat(p.getStatus()).isEqualTo("SUCCESS");assertThat(lease.getEndDate()).isEqualTo(end);verify(terms,never()).activate(anyLong(),any());}
 @Test void renewalFirstCallsContractActivationNotPaymentMonths(){var p=pending("RENEWAL");p.setMonths(1);service.settleSuccess(p,success());verify(terms).activate(7L,lease);verify(leases,never()).updateById(any(LeaseRecord.class));}
 @Test void closedLateSuccessPreservesFactsWithoutActivation(){var p=pending("FIRST");p.setStatus("CLOSED");service.settleSuccess(p,success());assertThat(p.getStatus()).isEqualTo("CLOSED");assertThat(p.getChannelTradeNo()).isEqualTo("MOCK-TRADE");assertThat(p.getSettlementIssue()).isNotBlank();verify(terms,never()).activate(anyLong(),any());}
 @Test void rejectedLeaseLateSuccessCannotRevive(){lease.setStatus("REJECTED");var p=pending("FIRST");service.settleSuccess(p,success());assertThat(p.getSettlementIssue()).isNotBlank();verify(leaseService,never()).applyTransition(any(),eq("ACTIVE"),anyString());}
 @Test void successBeyondChannelDeadlineIsFlagged(){var p=pending("RENT");p.setChannelExpiresAt(LocalDateTime.now().minusMinutes(10));service.settleSuccess(p,success());assertThat(p.getSettlementIssue()).isNotBlank();assertThat(p.getStatus()).isEqualTo("PENDING");}
 @Test void cancelledTermSuccessFlagged(){when(terms.canSettle(anyLong(),anyLong(),anyString())).thenReturn(false);var p=pending("RENEWAL");service.settleSuccess(p,success());assertThat(p.getSettlementIssue()).isNotBlank();verify(terms,never()).activate(anyLong(),any());}
 @Test void oldOrderWithoutSnapshotIsNotGuessed(){var p=pending("FIRST");p.setTermId(null);service.settleSuccess(p,success());assertThat(p.getSettlementIssue()).isNotBlank();verify(terms,never()).activate(anyLong(),any());}
 @Test void nullTradeDoesNotOpenTransaction(){service.settleSuccess(pending("RENT"),null);verifyNoInteractions(tx);}
 @Test void queryTransportFailurePreservesPending(){var p=pending("RENT");when(alipay.queryTrade(anyString())).thenThrow(new IllegalStateException("mock offline"));service.queryAndSettle(p);assertThat(p.getStatus()).isEqualTo("PENDING");}
 @Test void successfulQueryUsesExplicitSettlementBranch(){var p=pending("RENT");when(alipay.queryTrade(anyString())).thenReturn(success());var result=service.getStatus("TEST-ONLY");assertThat(result.getStatus()).isEqualTo("SUCCESS");}
 @Test void closeAnotherResidentsOrderForbidden(){var p=pending("RENT");p.setResidentId(9L);assertThatThrownBy(()->service.closePayment(p.getPaymentNo())).isInstanceOf(ForbiddenException.class);}
 @Test void successfulOrderCannotClose(){var p=pending("RENT");p.setStatus("SUCCESS");assertThatThrownBy(()->service.closePayment(p.getPaymentNo())).isInstanceOf(BusinessException.class);}
 @Test void alreadyClosedIsIdempotent(){var p=pending("RENT");p.setStatus("CLOSED");assertThat(service.closePayment(p.getPaymentNo())).isEqualTo(10L);}
 @Test void monetaryBreakdownReadsSnapshotNotCurrentRent(){var p=pending("RENT");lease.setMonthlyRent(new BigDecimal("99999"));var result=service.getStatus(p.getPaymentNo());assertThat(result.getRentAmount()).isEqualByComparingTo("9000");assertThat(result.getPeriodStart()).isEqualTo(p.getPeriodStart());}
 @Test void legacyUnknownAmountsAreNotInvented(){var p=pending("RENT");p.setRentAmount(null);p.setDepositAmount(null);var result=service.getStatus(p.getPaymentNo());assertThat(result.getRentAmount()).isNull();assertThat(result.getDepositAmount()).isNull();}
 @Test void channelFlagsReflectActualConfig(){when(wechat.enabled()).thenReturn(false);assertThat(service.channels()).extracting(v->v.isEnabled()).containsExactly(true,false);}
 @Test void renewalCannotHideAlreadyDueOriginalRent(){
  term.setStartDate(LocalDate.now().minusMonths(2));
  assertThatThrownBy(()->service.createRenewPayment(100L,legacyDto())).hasMessageContaining("先缴清");
  verify(payments,never()).insert(any(LeasePayment.class));verify(alipay,never()).createPayment(any(),anyString(),any());
 }
 @Test void anomalyRecordPreventsAnotherAttemptToChargeSameCoverage(){
  var p=pending("RENT");p.setSettlementIssue("REVIEW_REQUIRED");when(payments.selectList(any())).thenReturn(List.of(p));
  assertThatThrownBy(()->service.createRentPayment(100L,dto("ALIPAY"))).hasMessageContaining("待核对");
  verify(payments,never()).insert(any(LeasePayment.class));verify(alipay,never()).createPayment(any(),anyString(),any());
 }
 @Test void newFirstAndRentOrderDeadlinesShareFifteenMinuteRule() {
  var fixed=LocalDateTime.of(2026,10,16,12,0,0);
  try(var clock=mockStatic(LocalDateTime.class,CALLS_REAL_METHODS)) {
   clock.when(LocalDateTime::now).thenReturn(fixed);
   service.createRentPayment(100L,dto("ALIPAY"));
   assertThat(order.getChannelExpiresAt()).isEqualTo(fixed.plusMinutes(15));
   assertThat(order.getExpiresAt()).isEqualTo(fixed.plusMinutes(15).plusSeconds(30));
   first();var firstDto=new CreateFirstPaymentDTO();firstDto.setChannel("ALIPAY");
   service.createFirstPayment(100L,firstDto);
   assertThat(order.getChannelExpiresAt()).isEqualTo(fixed.plusMinutes(15));
   assertThat(order.getExpiresAt()).isEqualTo(fixed.plusMinutes(15).plusSeconds(30));
  }
 }
 @Test void continuingExistingOrderDoesNotReplaceItsHistoricalDeadlines() {
  var original=pending("RENT");var channel=LocalDateTime.now().plusHours(2);var local=channel.plusMinutes(5);
  original.setChannelExpiresAt(channel);original.setExpiresAt(local);when(payments.selectList(any())).thenReturn(List.of(original));
  service.createRentPayment(100L,dto("ALIPAY"));
  assertThat(original.getChannelExpiresAt()).isEqualTo(channel);assertThat(original.getExpiresAt()).isEqualTo(local);
  verify(payments,never()).insert(any(LeasePayment.class));verify(payments,never()).updateById(any(LeasePayment.class));
 }
 @Test void validFirstOrderCanBeResumedAfterMidnightWithoutCreatingAnotherOrder() {
  first();lease.setStartDate(LocalDate.now().minusDays(1));
  var original=pending("FIRST");var channel=LocalDateTime.now().plusMinutes(5);var local=channel.plusSeconds(30);
  original.setChannelExpiresAt(channel);original.setExpiresAt(local);when(payments.selectList(any())).thenReturn(List.of(original));
  var d=new CreateFirstPaymentDTO();d.setChannel("ALIPAY");
  var result=service.createFirstPayment(100L,d);
  assertThat(result.getPaymentNo()).isEqualTo(original.getPaymentNo());
  assertThat(original.getChannelExpiresAt()).isEqualTo(channel);assertThat(original.getExpiresAt()).isEqualTo(local);
  verify(payments,never()).insert(any(LeasePayment.class));
 }
 @Test void quoteAtExactSystemDeadlineDoesNotOfferExpiredOrder() {
  var fixed=LocalDateTime.of(2026,10,16,12,0,0);
  var expired=pending("RENT");expired.setChannelExpiresAt(fixed.minusSeconds(30));expired.setExpiresAt(fixed);
  when(payments.selectList(any())).thenReturn(List.of(expired));
  try(var clock=mockStatic(LocalDateTime.class,CALLS_REAL_METHODS)) {
   clock.when(LocalDateTime::now).thenReturn(fixed);
   var quote=service.quote(100L,"RENT",1);
   assertThat(quote.getPaymentNo()).isNull();assertThat(quote.getQuoteVersion()).isNotBlank();
   verify(alipay,never()).createPayment(any(),anyString(),any());verify(alipay,never()).queryTrade(anyString());
  }
 }
}
