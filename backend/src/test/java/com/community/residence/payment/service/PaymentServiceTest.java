package com.community.residence.payment.service;

import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.constant.PaymentStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.payment.config.PaymentProperties;
import com.community.residence.payment.dto.CreateRenewPaymentDTO;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.payment.service.channel.ChannelTradeResult;
import com.community.residence.payment.service.channel.PaymentChannelClient;
import com.community.residence.payment.vo.LeasePaymentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租约续约支付业务逻辑测试（渠道客户端全 mock，不触真实渠道）：
 * 创建（本人 ACTIVE 校验/金额计算/幂等/渠道禁用）、查单（落账+延展+双通知/幂等/
 * 渠道异常保留 PENDING）、关闭（PENDING 可关/SUCCESS 不可关）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentService 单元测试")
class PaymentServiceTest {

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

    /* alipay/wechat 为同接口实例，@InjectMocks 构造注入无法区分（同类型参数注入同一
       mock），改为显式构造保证两个渠道客户端各就其位 */
    private PaymentService paymentService;

    private LeaseRecord activeLease;

    @BeforeEach
    void setUp() {
        when(alipayChannelClient.channel()).thenReturn(PaymentChannel.ALIPAY);
        when(wechatChannelClient.channel()).thenReturn(PaymentChannel.WECHAT);
        when(alipayChannelClient.enabled()).thenReturn(true);
        when(wechatChannelClient.enabled()).thenReturn(true);
        when(paymentProperties.getNotifyUrl()).thenReturn(null);
        paymentService = new PaymentService(paymentMapper, leaseMapper,
                sysAdminCommunityMapper, notificationService, paymentProperties,
                alipayChannelClient, wechatChannelClient);

        activeLease = new LeaseRecord();
        activeLease.setId(100L);
        activeLease.setTenantId(1L);
        activeLease.setCommunityId(10L);
        activeLease.setHouseId(20L);
        activeLease.setStatus("ACTIVE");
        activeLease.setStartDate(LocalDate.now().minusMonths(12));
        activeLease.setEndDate(LocalDate.now().plusMonths(6));
        activeLease.setMonthlyRent(new BigDecimal("3000.00"));
    }

    /* ---------- 创建 ---------- */

    @Test
    @DisplayName("创建：本人 ACTIVE 租约成功且金额=月租×月数，凭据写入 qrCode/alipayForm 对应字段")
    void create_ownActiveLease_successWithServerComputedAmount() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>alipay</form>");

            LeasePaymentVO vo = paymentService.createRenewPayment(100L, dto(3, PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).startsWith("RENEW");
            assertThat(vo.getAmount()).isEqualByComparingTo(new BigDecimal("9000.00"));
            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(vo.getAlipayForm()).isEqualTo("<form>alipay</form>");
            ArgumentCaptor<LeasePayment> captor = ArgumentCaptor.forClass(LeasePayment.class);
            verify(paymentMapper).insert(captor.capture());
            assertThat(captor.getValue().getResidentId()).isEqualTo(1L);
            assertThat(captor.getValue().getMonths()).isEqualTo(3);
            verify(alipayChannelClient).createPayment(any(), eq("租约续租-3个月"), any());
        }
    }

    @Test
    @DisplayName("创建：微信渠道凭据写入 qrCode")
    void create_wechatChannel_qrCodeField() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(wechatChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("weixin://wxpay/code_url");

            LeasePaymentVO vo = paymentService.createRenewPayment(100L, dto(6, PaymentChannel.WECHAT));
            assertThat(vo.getQrCode()).isEqualTo("weixin://wxpay/code_url");
            assertThat(vo.getAlipayForm()).isNull();
        }
    }

    @Test
    @DisplayName("创建：非本人租约 403（ForbiddenException）")
    void create_othersLease_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);

            assertThatThrownBy(() -> paymentService.createRenewPayment(100L, dto(3, PaymentChannel.ALIPAY)))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("创建：非 ACTIVE 租约拒绝（STATE_TRANSITION_INVALID）")
    void create_nonActiveLease_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            activeLease.setStatus("MOVED_OUT");
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);

            assertThatThrownBy(() -> paymentService.createRenewPayment(100L, dto(3, PaymentChannel.ALIPAY)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅已生效租约可续约");
        }
    }

    @Test
    @DisplayName("创建：已有 PENDING 单幂等返回既有单，不重复创建")
    void create_pendingExists_returnsExisting() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);

            LeasePayment pending = pendingPayment();
            when(paymentMapper.selectOne(any())).thenReturn(pending);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>again</form>");

            LeasePaymentVO vo = paymentService.createRenewPayment(100L, dto(12, PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).isEqualTo("RENEW20260920000123");
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("创建：渠道禁用拒绝（PAYMENT_CHANNEL_DISABLED 5901）")
    void create_channelDisabled_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(alipayChannelClient.enabled()).thenReturn(false);

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createRenewPayment(100L, dto(3, PaymentChannel.ALIPAY)));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CHANNEL_DISABLED);
        }
    }

    @Test
    @DisplayName("创建：渠道下单失败关单并抛「发起支付失败」（PAYMENT_CREATE_FAILED 5902）")
    void create_channelCallFails_closesAndThrows() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenThrow(new RuntimeException("network down"));

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createRenewPayment(100L, dto(3, PaymentChannel.ALIPAY)));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CREATE_FAILED);

            ArgumentCaptor<LeasePayment> captor = ArgumentCaptor.forClass(LeasePayment.class);
            verify(paymentMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.CLOSED);
        }
    }

    /* ---------- 查单 ---------- */

    @Test
    @DisplayName("查单：PENDING 查到 SUCCESS → 回写 + 租期延展 N 个月 + 居民与管理员双通知")
    void getStatus_pendingPolledSuccess_settlesAndExtends() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);
            LeasePayment pending = pendingPayment();
            when(paymentMapper.selectOne(any())).thenReturn(pending);
            /* settleSuccess 内按 id 重读单据（幂等重入保护路径） */
            when(paymentMapper.selectById(pending.getId())).thenReturn(pending);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(alipayChannelClient.queryTrade("RENEW20260920000123"))
                    .thenReturn(new ChannelTradeResult("2026092022001", LocalDateTime.now()));
            when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(adminBinding(55L)));

            LeasePaymentVO vo = paymentService.getStatus("RENEW20260920000123");

            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(vo.getPaidAt()).isNotNull();
            /* 租期延展：原止期 + 3 个月（止期顺延口径，沿用管理员 renew 语义） */
            assertThat(activeLease.getEndDate())
                    .isEqualTo(LocalDate.now().plusMonths(6).plusMonths(3));
            /* 双通知：居民 1 条 + 绑定社区管理员 1 条，类型 LEASE / 来源 LEASE_PAYMENT */
            verify(notificationService, times(2)).create(anyLong(), eq(10L), anyString(),
                    anyString(), eq("LEASE"), eq("LEASE_PAYMENT"), eq(pending.getId()));
            verify(notificationService).create(eq(1L), eq(10L), eq("续租支付成功"),
                    anyString(), eq("LEASE"), eq("LEASE_PAYMENT"), eq(pending.getId()));
            verify(notificationService).create(eq(55L), eq(10L), eq("社区租约续约成功"),
                    anyString(), eq("LEASE"), eq("LEASE_PAYMENT"), eq(pending.getId()));
        }
    }

    @Test
    @DisplayName("查单幂等：已 SUCCESS 单重复查不重复延展、不再调渠道")
    void getStatus_alreadySuccess_noRepeatExtension() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);
            LeasePayment paid = pendingPayment();
            paid.setStatus(PaymentStatus.SUCCESS);
            paid.setPaidAt(LocalDateTime.now());
            when(paymentMapper.selectOne(any())).thenReturn(paid);

            LeasePaymentVO vo = paymentService.getStatus("RENEW20260920000123");

            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            verify(alipayChannelClient, never()).queryTrade(anyString());
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
            verify(notificationService, never()).create(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    @Test
    @DisplayName("查单：渠道异常吞掉保留 PENDING（网络不通不打挂查询）")
    void getStatus_channelError_keepsPending() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);
            LeasePayment pending = pendingPayment();
            when(paymentMapper.selectOne(any())).thenReturn(pending);
            when(alipayChannelClient.queryTrade("RENEW20260920000123"))
                    .thenThrow(new RuntimeException("connect timeout"));

            LeasePaymentVO vo = paymentService.getStatus("RENEW20260920000123");

            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
            verify(notificationService, never()).create(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    /* ---------- 关闭 ---------- */

    @Test
    @DisplayName("关闭：PENDING 可关（居民放弃支付）")
    void close_pending_closed() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            LeasePayment pending = pendingPayment();
            when(paymentMapper.selectOne(any())).thenReturn(pending);

            paymentService.closePayment("RENEW20260920000123");

            ArgumentCaptor<LeasePayment> captor = ArgumentCaptor.forClass(LeasePayment.class);
            verify(paymentMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.CLOSED);
        }
    }

    @Test
    @DisplayName("关闭：SUCCESS 不可关（已落账）")
    void close_success_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            LeasePayment paid = pendingPayment();
            paid.setStatus(PaymentStatus.SUCCESS);
            when(paymentMapper.selectOne(any())).thenReturn(paid);

            assertThatThrownBy(() -> paymentService.closePayment("RENEW20260920000123"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不可关闭");
        }
    }

    @Test
    @DisplayName("关闭：非本人支付单 403")
    void close_othersPayment_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            when(paymentMapper.selectOne(any())).thenReturn(pendingPayment());

            assertThatThrownBy(() -> paymentService.closePayment("RENEW20260920000123"))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    /* ---------- 渠道列表 ---------- */

    @Test
    @DisplayName("渠道列表：凭据齐全启用、未配置禁用")
    void channels_reflectEnabledState() {
        when(alipayChannelClient.enabled()).thenReturn(true);
        when(wechatChannelClient.enabled()).thenReturn(false);

        var channels = paymentService.channels();

        assertThat(channels).hasSize(2);
        assertThat(channels.get(0).getChannel()).isEqualTo(PaymentChannel.ALIPAY);
        assertThat(channels.get(0).isEnabled()).isTrue();
        assertThat(channels.get(1).getChannel()).isEqualTo(PaymentChannel.WECHAT);
        assertThat(channels.get(1).isEnabled()).isFalse();
    }

    /* ---------- 辅助 ---------- */

    private CreateRenewPaymentDTO dto(int months, String channel) {
        CreateRenewPaymentDTO dto = new CreateRenewPaymentDTO();
        dto.setMonths(months);
        dto.setChannel(channel);
        return dto;
    }

    private LeasePayment pendingPayment() {
        LeasePayment payment = new LeasePayment();
        payment.setId(500L);
        payment.setPaymentNo("RENEW20260920000123");
        payment.setLeaseId(100L);
        payment.setResidentId(1L);
        payment.setChannel(PaymentChannel.ALIPAY);
        payment.setMonths(3);
        payment.setAmount(new BigDecimal("9000.00"));
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return payment;
    }

    private SysAdminCommunity adminBinding(Long adminId) {
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setAdminId(adminId);
        binding.setCommunityId(10L);
        return binding;
    }

    /** 断言业务异常并返回（取 code 用） */
    private BusinessException catchBusinessException(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("预期抛出 BusinessException 但未抛出");
    }
}
