package com.community.residence.payment.service;

import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.DepositStatus;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.constant.PaymentStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.payment.config.PaymentProperties;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.payment.service.channel.ChannelTradeResult;
import com.community.residence.payment.service.channel.PaymentChannelClient;
import com.community.residence.payment.vo.LeasePaymentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R71 首期支付 × R72 押金状态 × R73 明细字段：
 * 首期支付成功 → 押金状态置「已缴」（押金为 0 时不置）；
 * 支付单 VO 补齐金额构成（rentAmount / depositAmount，服务端保证 amount = 两者之和）
 * 与覆盖租期（periodStart / periodEnd）及渠道流水号（channelTradeNo）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("首期支付押金状态与租约明细字段（R72/R73）")
class FirstPaymentDepositStatusTest {

    private static final Long LEASE_ID = 100L;
    private static final Long RESIDENT_ID = 1L;

    @Mock
    private LeasePaymentMapper paymentMapper;
    @Mock
    private LeaseRecordMapper leaseMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PaymentProperties paymentProperties;
    @Mock
    private PaymentChannelClient alipayChannelClient;
    @Mock
    private PaymentChannelClient wechatChannelClient;

    private PaymentService paymentService;

    private LeaseRecord lease;

    @BeforeEach
    void setUp() {
        when(alipayChannelClient.channel()).thenReturn(PaymentChannel.ALIPAY);
        when(wechatChannelClient.channel()).thenReturn(PaymentChannel.WECHAT);
        when(alipayChannelClient.enabled()).thenReturn(true);
        when(wechatChannelClient.enabled()).thenReturn(true);
        when(paymentProperties.getNotifyUrl()).thenReturn(null);
        paymentService = new PaymentService(paymentMapper, leaseMapper, sysAdminCommunityMapper,
                notificationService, paymentProperties, alipayChannelClient, wechatChannelClient);
        SettlementTestInfrastructure.install(paymentService, paymentMapper, leaseMapper);

        lease = new LeaseRecord();
        lease.setId(LEASE_ID);
        lease.setTenantId(RESIDENT_ID);
        lease.setCommunityId(10L);
        lease.setHouseId(20L);
        lease.setStatus("ACTIVE");
        lease.setDepositStatus(DepositStatus.UNPAID);
        lease.setMonthlyRent(new BigDecimal("3000.00"));
        lease.setDeposit(new BigDecimal("6000.00"));
        lease.setStartDate(LocalDate.now().minusMonths(12));
        lease.setEndDate(LocalDate.now().plusMonths(6));
        when(leaseMapper.selectById(LEASE_ID)).thenReturn(lease);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of());
    }

    private LeasePayment firstPayment(String amount) {
        LeasePayment payment = new LeasePayment();
        payment.setId(500L);
        payment.setPaymentNo("FIRST20260920000123");
        payment.setLeaseId(LEASE_ID);
        payment.setResidentId(RESIDENT_ID);
        payment.setPaymentType("FIRST");
        payment.setChannel(PaymentChannel.ALIPAY);
        payment.setMonths(1);
        payment.setAmount(new BigDecimal(amount));
        payment.setStatus(PaymentStatus.PENDING);
        return payment;
    }

    @Test
    @DisplayName("首期支付成功：押金状态 UNPAID → PAID（R72），租约状态与止期不变（与签约解耦）")
    void firstPaymentSettled_marksDepositPaid() {
        LeasePayment payment = firstPayment("9000.00");
        LocalDate endDateBefore = lease.getEndDate();
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.selectById(500L)).thenReturn(payment);
        when(alipayChannelClient.queryTrade("FIRST20260920000123"))
                .thenReturn(new ChannelTradeResult("2026092022001", LocalDateTime.now()));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            LeasePaymentVO vo = paymentService.getStatus("FIRST20260920000123");

            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            /* 押金部分入账 → 已缴；租约状态与止期不动（首期与签约解耦） */
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.PAID);
            assertThat(lease.getStatus()).isEqualTo("ACTIVE");
            assertThat(lease.getEndDate()).isEqualTo(endDateBefore);
        }
    }

    @Test
    @DisplayName("R73：首期单金额构成 = 月租 + 押金，覆盖首月；渠道流水号回填 VO")
    void firstPayment_voCarriesAmountBreakdown() {
        LeasePayment payment = firstPayment("9000.00");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.selectById(500L)).thenReturn(payment);
        when(alipayChannelClient.queryTrade("FIRST20260920000123"))
                .thenReturn(new ChannelTradeResult("2026092022001", LocalDateTime.now()));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            LeasePaymentVO vo = paymentService.getStatus("FIRST20260920000123");

            assertThat(vo.getRentAmount()).isEqualByComparingTo("3000.00");
            assertThat(vo.getDepositAmount()).isEqualByComparingTo("6000.00");
            assertThat(vo.getRentAmount().add(vo.getDepositAmount())).isEqualByComparingTo(vo.getAmount());
            assertThat(vo.getPeriodStart()).isEqualTo(lease.getStartDate());
            assertThat(vo.getPeriodEnd()).isEqualTo(lease.getStartDate().plusMonths(1));
            assertThat(vo.getChannelTradeNo()).isEqualTo("2026092022001");
        }
    }

    @Test
    @DisplayName("押金为 0 的首期单：不置「已缴」（可退金额为 0，避免误导性状态）")
    void firstPayment_zeroDeposit_keepsUnpaid() {
        lease.setDeposit(BigDecimal.ZERO);
        LeasePayment payment = firstPayment("3000.00");
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.selectById(500L)).thenReturn(payment);
        when(alipayChannelClient.queryTrade("FIRST20260920000123"))
                .thenReturn(new ChannelTradeResult("2026092022001", LocalDateTime.now()));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            LeasePaymentVO vo = paymentService.getStatus("FIRST20260920000123");

            assertThat(vo.getDepositAmount()).isEqualByComparingTo("0");
            assertThat(vo.getRentAmount()).isEqualByComparingTo("3000.00");
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.UNPAID);
        }
    }

    @Test
    @DisplayName("R73：续期单金额构成全为租金部分，覆盖「止期前推 months 个月 ~ 顺延后止期」")
    void renewalPayment_voCarriesPeriod() {
        LeasePayment payment = firstPayment("9000.00");
        payment.setPaymentType("RENEWAL");
        payment.setMonths(3);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.selectById(500L)).thenReturn(payment);
        when(alipayChannelClient.queryTrade("FIRST20260920000123"))
                .thenReturn(new ChannelTradeResult("2026092022001", LocalDateTime.now()));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);

            LeasePaymentVO vo = paymentService.getStatus("FIRST20260920000123");

            /* 续期顺延 3 个月：覆盖区间 = 新止期前推 3 个月 ~ 新止期 */
            LocalDate newEndDate = LocalDate.now().plusMonths(6).plusMonths(3);
            assertThat(vo.getRentAmount()).isEqualByComparingTo("9000.00");
            assertThat(vo.getDepositAmount()).isEqualByComparingTo("0");
            assertThat(vo.getPeriodEnd()).isEqualTo(newEndDate);
            assertThat(vo.getPeriodStart()).isEqualTo(newEndDate.minusMonths(3));
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.UNPAID);
        }
    }
}
