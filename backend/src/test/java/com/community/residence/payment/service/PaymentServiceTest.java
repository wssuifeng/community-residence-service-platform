package com.community.residence.payment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.DepositStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.constant.PaymentStatus;
import com.community.residence.common.constant.PaymentType;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.result.PageVO;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
 * 创建（本人 ACTIVE 校验/金额计算/幂等/渠道禁用）、首期单幂等续接与「首期只收一次」
 * 及类型隔离（DEF-068）、查单（落账+延展+双通知/幂等/渠道异常保留 PENDING）、
 * 关闭（PENDING 可关/SUCCESS 不可关）、首期发起并发锁契约与分页租约批量装载
 * （DEF-107/DEF-127，OCR 修复轮）。
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
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    /* alipay/wechat 为同接口实例，@InjectMocks 构造注入无法区分（同类型参数注入同一
       mock），改为显式构造保证两个渠道客户端各就其位 */
    private PaymentService paymentService;

    private LeaseRecord activeLease;

    /* LambdaQueryWrapper 的条件值断言需渲染 SQL 片段，而列名解析依赖 MP TableInfo 缓存：
       纯 Mockito 单测没有 MyBatis 启动流程，须显式初始化实体表信息 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), LeasePayment.class);
    }

    @BeforeEach
    void setUp() {
        when(alipayChannelClient.channel()).thenReturn(PaymentChannel.ALIPAY);
        when(wechatChannelClient.channel()).thenReturn(PaymentChannel.WECHAT);
        when(alipayChannelClient.enabled()).thenReturn(true);
        when(wechatChannelClient.enabled()).thenReturn(true);
        when(paymentProperties.getNotifyUrl()).thenReturn(null);
        /* DEF-107 首期并发锁：走 createFirstPayment 的用例统一经锁 mock（获取即成功），
           未触达锁的用例靠 LENIENT 宽容闲置桩 */
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        paymentService = new PaymentService(paymentMapper, leaseMapper,
                sysAdminCommunityMapper, notificationService, paymentProperties,
                alipayChannelClient, wechatChannelClient);
        paymentService.setRedissonClient(redissonClient);

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
            /* 双通知：居民 1 条（RESIDENT 身份空间）+ 绑定社区管理员 1 条（USER 身份空间），
               类型 LEASE / 来源 LEASE_PAYMENT——收件人身份空间由入口方法显式声明（DEF-067） */
            verify(notificationService).createForResident(eq(1L), eq(10L), eq("续租支付成功"),
                    anyString(), eq("LEASE"), eq("LEASE_PAYMENT"), eq(pending.getId()));
            verify(notificationService).createForUser(eq(55L), eq(10L), eq("社区租约续约成功"),
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
            verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
            verify(notificationService, never()).createForUser(anyLong(), anyLong(), anyString(),
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
            verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
            verify(notificationService, never()).createForUser(anyLong(), anyLong(), anyString(),
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

    /* ---------- 首期费用（R71） ---------- */

    @Test
    @DisplayName("首期：本人 ACTIVE 租约成功，金额=月租+押金、months=1、类型 FIRST、单号前缀 FIRST")
    void createFirstPayment_ownActiveLease_serverComputedAmount() {
        activeLease.setDeposit(new BigDecimal("6000.00"));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>first</form>");

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).startsWith("FIRST");
            assertThat(vo.getPaymentType()).isEqualTo("FIRST");
            assertThat(vo.getMonths()).isEqualTo(1);
            assertThat(vo.getAmount()).isEqualByComparingTo(new BigDecimal("9000.00"));
            assertThat(vo.getAlipayForm()).isEqualTo("<form>first</form>");
            ArgumentCaptor<LeasePayment> captor = ArgumentCaptor.forClass(LeasePayment.class);
            verify(paymentMapper).insert(captor.capture());
            assertThat(captor.getValue().getPaymentType()).isEqualTo("FIRST");
            assertThat(captor.getValue().getResidentId()).isEqualTo(1L);
            verify(alipayChannelClient).createPayment(any(), eq("租约首期费用（首月租金+押金）"), any());
        }
    }

    @Test
    @DisplayName("首期只收一次：已有 FIRST+SUCCESS 单拒绝 PAYMENT_ALREADY_EXISTS(5904)")
    void createFirstPayment_settledOrder_rejected() {
        activeLease.setDeposit(new BigDecimal("6000.00"));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(1L);

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY)));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_EXISTS);
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("首期：非 ACTIVE 租约拒绝 LEASE_STATUS_INVALID(5602)")
    void createFirstPayment_nonActiveLease_rejected() {
        activeLease.setStatus("TO_SIGN");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY)));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LEASE_STATUS_INVALID);
        }
    }

    @Test
    @DisplayName("首期：非本人租约 403")
    void createFirstPayment_othersLease_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);

            assertThatThrownBy(() -> paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY)))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    /* ---------- 首期订单死锁修复（DEF-068） ---------- */

    @Test
    @DisplayName("DEF-068 幂等续接：已有 FIRST+PENDING 单则原样返回同一单 + 重取渠道凭据，不新建")
    void createFirstPayment_pendingOrder_resumesSameOrderWithFreshCredential() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            LeasePayment pendingFirst = pendingFirstPayment();
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(pendingFirst);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>resume</form>");

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).isEqualTo(pendingFirst.getPaymentNo());
            assertThat(vo.getPaymentType()).isEqualTo(PaymentType.FIRST);
            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(vo.getAlipayForm()).isEqualTo("<form>resume</form>");
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
            /* 重取凭据用首期订单标题（与续期文案区分，居民收银台可见） */
            verify(alipayChannelClient).createPayment(any(), eq("租约首期费用（首月租金+押金）"), any());
        }
    }

    @Test
    @DisplayName("DEF-068 幂等续接：关窗后渠道凭据重取失败仍返回原单（不阻断，居民可关单重开）")
    void createFirstPayment_pendingOrder_credentialFailure_returnsOrderWithoutCredential() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            LeasePayment pendingFirst = pendingFirstPayment();
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(pendingFirst);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenThrow(new RuntimeException("channel down"));

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).isEqualTo(pendingFirst.getPaymentNo());
            assertThat(vo.getAlipayForm()).isNull();
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("DEF-068 防二次收款：SUCCESS 与残留 PENDING 并存时仍拒 5904（不返回可支付单）")
    void createFirstPayment_settledWithStrayPending_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(1L);
            when(paymentMapper.selectOne(any())).thenReturn(pendingFirstPayment());

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_EXISTS);
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
            verify(alipayChannelClient, never()).createPayment(any(), anyString(), any());
        }
    }

    @Test
    @DisplayName("DEF-068 可用性：仅存在 FIRST+CLOSED（已关闭）时允许重新发起新单，拒绝判定只查 SUCCESS")
    void createFirstPayment_closedOrder_allowsNewOrder() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            /* 库中仅剩 CLOSED 单：SUCCESS 计数 0、PENDING 查询无命中 */
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>reopen</form>");

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).startsWith("FIRST");
            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(vo.getAlipayForm()).isEqualTo("<form>reopen</form>");
            verify(paymentMapper).insert(any(LeasePayment.class));
            /* 防回归：拒绝判定只按 SUCCESS（若有人把 CLOSED 加回拒绝条件，本断言失败） */
            ArgumentCaptor<Wrapper<LeasePayment>> captor = wrapperCaptor();
            verify(paymentMapper, times(2)).selectCount(captor.capture());
            assertThat(allParamValues(captor.getAllValues()))
                    .contains(PaymentStatus.SUCCESS, PaymentType.FIRST)
                    .doesNotContain(PaymentStatus.CLOSED);
        }
    }

    @Test
    @DisplayName("DEF-068 类型隔离：同租约存在续期单不影响首期发起，首期判定只查 FIRST")
    void createFirstPayment_renewalOrderOfSameLease_doesNotInterfere() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            /* 库中续期单（PENDING/SUCCESS）不参与首期判定 */
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>first</form>");

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentType()).isEqualTo(PaymentType.FIRST);
            verify(paymentMapper).insert(any(LeasePayment.class));
            ArgumentCaptor<Wrapper<LeasePayment>> oneCaptor = wrapperCaptor();
            verify(paymentMapper).selectOne(oneCaptor.capture());
            assertThat(paramValues(oneCaptor.getValue()))
                    .contains(PaymentType.FIRST, PaymentStatus.PENDING)
                    .doesNotContain(PaymentType.RENEWAL);
        }
    }

    @Test
    @DisplayName("DEF-068 类型隔离：同租约存在首期单不影响续期发起，续期判定只查 RENEWAL/空类型")
    void createRenewPayment_firstPaymentOfSameLease_doesNotInterfere() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            /* 库中首期单（含 PENDING）不参与续期判定 */
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>renew</form>");

            LeasePaymentVO vo = paymentService.createRenewPayment(100L, dto(3, PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).startsWith("RENEW");
            assertThat(vo.getPaymentType()).isEqualTo(PaymentType.RENEWAL);
            verify(paymentMapper).insert(any(LeasePayment.class));
            ArgumentCaptor<Wrapper<LeasePayment>> captor = wrapperCaptor();
            verify(paymentMapper).selectOne(captor.capture());
            assertThat(paramValues(captor.getValue()))
                    .contains(PaymentType.RENEWAL, PaymentStatus.PENDING)
                    .doesNotContain(PaymentType.FIRST);
        }
    }

    /* ---------- 已到期租约不得支付首期（DEF-069 服务端兜底） ---------- */

    @Test
    @DisplayName("DEF-069：未到期 ACTIVE 租约可付首期（止期=今天+1，边界外侧）")
    void createFirstPayment_notExpiredLease_allowed() {
        activeLease.setEndDate(LocalDate.now().plusDays(1));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>first</form>");

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).startsWith("FIRST");
            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentMapper).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("DEF-069：已到期 ACTIVE 租约（止期=昨天）拒 LEASE_EXPIRED(5606)，不建单不取凭据")
    void createFirstPayment_expiredLease_rejected() {
        LocalDate expiredOn = LocalDate.now().minusDays(1);
        activeLease.setEndDate(expiredOn);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LEASE_EXPIRED);
            assertThat(ex.getMessage())
                    .contains(expiredOn.toString())
                    .contains("请先续租或办理退租");
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
            verify(alipayChannelClient, never()).createPayment(any(), anyString(), any());
        }
    }

    @Test
    @DisplayName("DEF-069 边界：止期=当天（今天到期）不算已到期，仍可支付首期（与 expiryFlag 口径一致）")
    void createFirstPayment_endDateToday_allowed() {
        LocalDate today = LocalDate.now();
        activeLease.setEndDate(today);
        /* 口径直证：expiryFlag/到期提醒/前端 expiry-badge 同源判定——严格早于当天才算已到期 */
        assertThat(LeaseStatus.isExpired(today.minusDays(1), today)).isTrue();
        assertThat(LeaseStatus.isExpired(today, today)).isFalse();
        assertThat(LeaseStatus.isExpired(today.plusDays(1), today)).isFalse();

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>today</form>");

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).startsWith("FIRST");
            assertThat(vo.getAlipayForm()).isEqualTo("<form>today</form>");
            verify(paymentMapper).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("DEF-069 优先级：已到期但已有 FIRST+SUCCESS 单仍按 DEF-068 拒 5904（不被到期校验盖过）")
    void createFirstPayment_expiredLeaseWithSettledOrder_rejectedAs5904() {
        activeLease.setEndDate(LocalDate.now().minusDays(3));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(1L);

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_EXISTS);
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("DEF-069：已到期租约上遗留的 FIRST+PENDING 单不续接（到期校验先于幂等续接）")
    void createFirstPayment_expiredLeaseWithPendingOrder_rejected() {
        activeLease.setEndDate(LocalDate.now().minusDays(2));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(pendingFirstPayment());

            BusinessException ex = catchBusinessException(
                    () -> paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LEASE_EXPIRED);
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
            verify(alipayChannelClient, never()).createPayment(any(), anyString(), any());
        }
    }

    @Test
    @DisplayName("DEF-069：已到期租约的续期支付不受影响（到期正是续期场景），照常建 RENEW 单")
    void createRenewPayment_expiredLease_stillAllowed() {
        activeLease.setEndDate(LocalDate.now().minusDays(5));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectOne(any())).thenReturn(null);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>renew</form>");

            LeasePaymentVO vo = paymentService.createRenewPayment(100L, dto(3, PaymentChannel.ALIPAY));

            assertThat(vo.getPaymentNo()).startsWith("RENEW");
            assertThat(vo.getPaymentType()).isEqualTo(PaymentType.RENEWAL);
            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentMapper).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("首期查单落账：支付成功**不顺延** end_date，双方通知来源为 LEASE_PAYMENT")
    void getStatus_firstPaymentSuccess_keepsEndDate() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(true);
            LeasePayment first = pendingPayment();
            first.setPaymentType("FIRST");
            first.setMonths(1);
            first.setAmount(new BigDecimal("9000.00"));
            LocalDate endDateBefore = activeLease.getEndDate();
            when(paymentMapper.selectOne(any())).thenReturn(first);
            when(paymentMapper.selectById(first.getId())).thenReturn(first);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(alipayChannelClient.queryTrade("RENEW20260920000123"))
                    .thenReturn(new ChannelTradeResult("2026092022002", LocalDateTime.now()));
            when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(adminBinding(55L)));

            LeasePaymentVO vo = paymentService.getStatus("RENEW20260920000123");

            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            /* 首期与租约生效解耦：止期不顺延（R72 起首期落账仅额外回写押金状态 → 已缴，
               故不再断言「完全不回写租约」，改为断言回写内容不含止期变更） */
            assertThat(activeLease.getEndDate()).isEqualTo(endDateBefore);
            ArgumentCaptor<LeaseRecord> leaseCaptor = ArgumentCaptor.forClass(LeaseRecord.class);
            verify(leaseMapper).updateById(leaseCaptor.capture());
            assertThat(leaseCaptor.getValue().getEndDate()).isEqualTo(endDateBefore);
            assertThat(leaseCaptor.getValue().getDepositStatus()).isEqualTo(DepositStatus.PAID);
            verify(notificationService).createForResident(eq(1L), eq(10L), eq("首期费用支付成功"),
                    anyString(), eq("LEASE"), eq("LEASE_PAYMENT"), eq(first.getId()));
            verify(notificationService).createForUser(eq(55L), eq(10L), eq("社区租约首期费用到账"),
                    anyString(), eq("LEASE"), eq("LEASE_PAYMENT"), eq(first.getId()));
        }
    }

    @Test
    @DisplayName("租约支付单列表：本人租约可见且按类型返回（R71 契约补充）")
    void listByLease_returnsPayments() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            mocked.when(() -> SecurityUtils.hasRole(com.community.residence.common.constant.RoleConstants.RESIDENT))
                    .thenReturn(true);
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            LeasePayment first = pendingPayment();
            first.setPaymentType("FIRST");
            when(paymentMapper.selectList(any())).thenReturn(List.of(first));

            List<LeasePaymentVO> list = paymentService.listByLease(100L);

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getPaymentType()).isEqualTo("FIRST");
        }
    }

    /* ---------- 首期并发锁与分页批量装载（OCR 修复轮） ---------- */

    @Test
    @DisplayName("DEF-107 并发防线：首期发起持同租约锁（5 秒等待），锁内续接既有 PENDING 单不新建，锁必然释放")
    void createFirstPayment_lockGuarded_pendingResumedAndLockReleased() throws Exception {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            LeasePayment pendingFirst = pendingFirstPayment();
            when(leaseMapper.selectById(100L)).thenReturn(activeLease);
            when(paymentMapper.selectCount(any())).thenReturn(0L);
            when(paymentMapper.selectOne(any())).thenReturn(pendingFirst);
            when(alipayChannelClient.createPayment(any(), anyString(), any()))
                    .thenReturn("<form>resume</form>");

            LeasePaymentVO vo = paymentService.createFirstPayment(100L, firstDto(PaymentChannel.ALIPAY));

            /* 锁契约：按租约粒度取锁、5 秒 tryLock、finally 释放——锁缺失则本组断言全部失守 */
            verify(redissonClient).getLock("lock:payment:first:100");
            verify(lock).tryLock(5, TimeUnit.SECONDS);
            verify(lock).unlock();
            /* 并发后到请求在锁内读到既有 PENDING：原样续接同一单，不再新建（无双 PENDING 双收款） */
            assertThat(vo.getPaymentNo()).isEqualTo(pendingFirst.getPaymentNo());
            assertThat(vo.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentMapper, never()).insert(any(LeasePayment.class));
        }
    }

    @Test
    @DisplayName("DEF-127 分页批量装载：按 distinct leaseId 一次 selectBatchIds 装配明细，不逐行 selectById")
    void page_leasesLoadedInOneBatch_noRowByRowLookup() {
        LeasePayment first1 = firstPaymentOfLease(1L, "1000.00");
        LeasePayment first2 = firstPaymentOfLease(2L, "2000.00");
        LeasePayment first3 = firstPaymentOfLease(3L, "3000.00");
        Page<LeasePayment> pageResult = new Page<>(1, 10, 3);
        pageResult.setRecords(new ArrayList<>(List.of(first1, first2, first3)));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(1L);
            when(paymentMapper.selectPage(any(), any())).thenReturn(pageResult);
            when(leaseMapper.selectBatchIds(any())).thenReturn(List.of(
                    leaseOf(1L, "1000.00"), leaseOf(2L, "2000.00"), leaseOf(3L, "3000.00")));

            PageVO<LeasePaymentVO> vo = paymentService.page(1, 10, null);

            /* 一次批量装载全部租约，零逐行查询（N+1 消除） */
            verify(leaseMapper, times(1)).selectBatchIds(any());
            verify(leaseMapper, never()).selectById(any());
            assertThat(vo.getRecords()).hasSize(3);
            /* 明细按各自租约装配（证明批量 Map 命中，非空装载）：首期单租金部分 = 各租约月租 */
            assertThat(vo.getRecords())
                    .extracting(LeasePaymentVO::getRentAmount)
                    .containsExactly(new BigDecimal("1000.00"),
                            new BigDecimal("2000.00"), new BigDecimal("3000.00"));
        }
    }

    /* ---------- 辅助 ---------- */

    private CreateRenewPaymentDTO dto(int months, String channel) {
        CreateRenewPaymentDTO dto = new CreateRenewPaymentDTO();
        dto.setMonths(months);
        dto.setChannel(channel);
        return dto;
    }

    private com.community.residence.payment.dto.CreateFirstPaymentDTO firstDto(String channel) {
        com.community.residence.payment.dto.CreateFirstPaymentDTO dto =
                new com.community.residence.payment.dto.CreateFirstPaymentDTO();
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

    /** 已存在但未支付的「首期」单（DEF-068 幂等续接场景；居民关窗后遗留） */
    private LeasePayment pendingFirstPayment() {
        LeasePayment payment = pendingPayment();
        payment.setId(600L);
        payment.setPaymentNo("FIRST20260920000999");
        payment.setPaymentType(PaymentType.FIRST);
        payment.setMonths(1);
        return payment;
    }

    /** 分页用例行：指定租约的首期 PENDING 单（DEF-127 批量装载场景） */
    private LeasePayment firstPaymentOfLease(Long leaseId, String amount) {
        LeasePayment payment = new LeasePayment();
        payment.setId(500L + leaseId);
        payment.setPaymentNo("FIRST20260923" + String.format("%06d", leaseId));
        payment.setLeaseId(leaseId);
        payment.setResidentId(1L);
        payment.setPaymentType(PaymentType.FIRST);
        payment.setChannel(PaymentChannel.ALIPAY);
        payment.setMonths(1);
        payment.setAmount(new BigDecimal(amount));
        payment.setStatus(PaymentStatus.PENDING);
        return payment;
    }

    /** 分页用例租约：月租 = amount（首期明细租金部分直接对照），止期未到、状态生效 */
    private LeaseRecord leaseOf(Long id, String monthlyRent) {
        LeaseRecord lease = new LeaseRecord();
        lease.setId(id);
        lease.setTenantId(1L);
        lease.setCommunityId(10L);
        lease.setHouseId(20L + id);
        lease.setStatus("ACTIVE");
        lease.setMonthlyRent(new BigDecimal(monthlyRent));
        lease.setStartDate(LocalDate.now().minusMonths(12));
        lease.setEndDate(LocalDate.now().plusMonths(6));
        return lease;
    }

    /** 查询条件包装器捕获（断言身份/类型/状态过滤口径，防回归为过严或过宽条件） */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<LeasePayment>> wrapperCaptor() {
        return (ArgumentCaptor<Wrapper<LeasePayment>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
    }

    private List<Object> paramValues(Wrapper<LeasePayment> wrapper) {
        /* MP 条件值为懒渲染：先取 SQL 片段触发渲染，条件值才写入 paramNameValuePairs */
        wrapper.getSqlSegment();
        return new ArrayList<>(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values());
    }

    private List<Object> allParamValues(List<Wrapper<LeasePayment>> wrappers) {
        List<Object> values = new ArrayList<>();
        wrappers.forEach(wrapper -> values.addAll(paramValues(wrapper)));
        return values;
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
