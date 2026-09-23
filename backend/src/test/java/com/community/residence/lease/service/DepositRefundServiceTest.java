package com.community.residence.lease.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.DepositRefundStatus;
import com.community.residence.common.constant.DepositStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.constant.PaymentStatus;
import com.community.residence.common.constant.PaymentType;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.lease.dto.CreateDepositRefundDTO;
import com.community.residence.lease.entity.LeaseDepositRefund;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseDepositRefundMapper;
import com.community.residence.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.vo.DepositRefundVO;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.payment.service.channel.ChannelRefundResult;
import com.community.residence.payment.service.channel.PaymentChannelClient;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R72 押金在线退还：金额上限（5704）、押金状态门禁（5703）、原渠道一致性（5705）、
 * 请求号冲突与幂等（5706 / 同号返回既有结果）、凭据门控（5901）、租约前置（5602）、
 * 渠道成功/失败的状态联动（REFUNDING → REFUNDED / 回落 PAID）、居民越权（403）。
 * DEF-103 修复轮新增：并发第二请求锁内重读 REFUNDING 拦截（5703）、insert 撞
 * uk_deposit_out_request_no 重查幂等返回、部分退款成功保持 PAID、累计达实缴才
 * REFUNDED、实缴口径按登记押金（月租事后编辑不影响）。
 * 事务边界（三段式）：TransactionTemplate mock 直执行回调（无真实事务，业务语义不变），
 * RLock mock 恒返回获锁（真实互斥由 JdkSynchronizedRLock 型集成测试承载）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("押金退还（R72）")
class DepositRefundServiceTest {

    private static final Long LEASE_ID = 100L;
    private static final Long COMMUNITY_ID = 10L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long OPERATOR_ID = 7L;
    private static final String OUT_REQUEST_NO = "DR20260922000001";

    @Mock
    private LeaseDepositRefundMapper refundMapper;
    @Mock
    private LeaseRecordMapper leaseMapper;
    @Mock
    private LeasePaymentMapper paymentMapper;
    @Mock
    private LeaseMoveOutApplicationMapper moveOutApplicationMapper;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private PaymentChannelClient alipayChannelClient;
    @Mock
    private PaymentChannelClient wechatChannelClient;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private TransactionTemplate transactionTemplate;

    /* alipay/wechat 为同接口实例，@InjectMocks 无法区分，改为显式构造（口径同 PaymentServiceTest） */
    private DepositRefundService service;

    private LeaseRecord lease;
    private LeasePayment firstPayment;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(alipayChannelClient.channel()).thenReturn(PaymentChannel.ALIPAY);
        when(wechatChannelClient.channel()).thenReturn(PaymentChannel.WECHAT);
        when(alipayChannelClient.enabled()).thenReturn(true);
        when(wechatChannelClient.enabled()).thenReturn(true);

        /* DEF-103：分布式锁恒获锁；TransactionTemplate 直执行回调（单测无真实事务） */
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        doAnswer(inv -> {
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new DepositRefundService(refundMapper, leaseMapper, paymentMapper,
                moveOutApplicationMapper, changeLogService, notificationService, sysUserMapper,
                alipayChannelClient, wechatChannelClient, redissonClient, transactionTemplate);

        /* 已搬出租约：月租 3000 + 登记押金 6000（存在 SUCCESS 首期单即实缴，DEF-103 口径） */
        lease = new LeaseRecord();
        lease.setId(LEASE_ID);
        lease.setTenantId(RESIDENT_ID);
        lease.setCommunityId(COMMUNITY_ID);
        lease.setHouseId(20L);
        lease.setStatus("MOVED_OUT");
        lease.setDepositStatus(DepositStatus.PAID);
        lease.setMonthlyRent(new BigDecimal("3000.00"));
        lease.setDeposit(new BigDecimal("6000.00"));
        lease.setStartDate(LocalDate.now().minusMonths(12));
        lease.setEndDate(LocalDate.now());

        firstPayment = new LeasePayment();
        firstPayment.setId(500L);
        firstPayment.setPaymentNo("FIRST20260920000123");
        firstPayment.setLeaseId(LEASE_ID);
        firstPayment.setPaymentType(PaymentType.FIRST);
        firstPayment.setChannel(PaymentChannel.ALIPAY);
        firstPayment.setAmount(new BigDecimal("9000.00"));
        firstPayment.setStatus(PaymentStatus.SUCCESS);

        when(leaseMapper.selectById(LEASE_ID)).thenReturn(lease);
        when(paymentMapper.selectList(any())).thenReturn(List.of(firstPayment));
        when(refundMapper.selectList(any())).thenReturn(List.of());
        when(refundMapper.selectOne(any())).thenReturn(null);
    }

    private CreateDepositRefundDTO dto(BigDecimal amount, String channel) {
        CreateDepositRefundDTO dto = new CreateDepositRefundDTO();
        dto.setAmount(amount);
        dto.setChannel(channel);
        dto.setOutRequestNo(OUT_REQUEST_NO);
        dto.setRemark("退租结算");
        return dto;
    }

    private BusinessException catchBusiness(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("预期抛出 BusinessException 但未抛出");
    }

    @Test
    @DisplayName("① 金额超实缴押金 → 5704（实缴 6000，申请 6001 拒绝且不落单、不调渠道）")
    void amountExceedsPaidDeposit_throws5704() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            BusinessException ex = catchBusiness(() ->
                    service.request(LEASE_ID, dto(new BigDecimal("6001.00"), null)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPOSIT_AMOUNT_EXCEEDED);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(5704);
            verify(refundMapper, never()).insert(any(LeaseDepositRefund.class));
            verify(alipayChannelClient, never()).refund(any(), anyString(), any(), anyString());
        }
    }

    @Test
    @DisplayName("② 押金状态非「已缴」→ 5703（未缴 / 已退还 / 退还中 / 已抵扣一律拒绝）")
    void depositStatusNotPaid_throws5703() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            for (String status : List.of(DepositStatus.UNPAID, DepositStatus.REFUNDING,
                    DepositStatus.REFUNDED, DepositStatus.OFFSET)) {
                lease.setDepositStatus(status);
                BusinessException ex = catchBusiness(() -> service.request(LEASE_ID, dto(null, null)));
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPOSIT_STATUS_NOT_REFUNDABLE);
                assertThat(ex.getErrorCode().getCode()).isEqualTo(5703);
            }
            verify(refundMapper, never()).insert(any(LeaseDepositRefund.class));
        }
    }

    @Test
    @DisplayName("③ 客户端渠道与实缴渠道不一致 → 5705（不允许跨渠道退款）")
    void channelMismatch_throws5705() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            BusinessException ex = catchBusiness(() ->
                    service.request(LEASE_ID, dto(null, PaymentChannel.WECHAT)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPOSIT_CHANNEL_MISMATCH);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(5705);
            verify(wechatChannelClient, never()).refund(any(), anyString(), any(), anyString());
        }
    }

    @Test
    @DisplayName("④ 请求号冲突：同号但金额/租约不一致 → 5706")
    void outRequestNoConflict_throws5706() {
        LeaseDepositRefund existing = existingRefund(new BigDecimal("100.00"), DepositRefundStatus.PENDING);
        when(refundMapper.selectOne(any())).thenReturn(existing);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            /* 同号不同金额 */
            BusinessException ex = catchBusiness(() ->
                    service.request(LEASE_ID, dto(new BigDecimal("200.00"), null)));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPOSIT_OUT_REQUEST_CONFLICT);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(5706);

            /* 同号不同租约 */
            LeaseRecord other = new LeaseRecord();
            other.setId(999L);
            other.setTenantId(RESIDENT_ID);
            other.setCommunityId(COMMUNITY_ID);
            other.setStatus("MOVED_OUT");
            other.setDepositStatus(DepositStatus.PAID);
            other.setMonthlyRent(new BigDecimal("3000.00"));
            when(leaseMapper.selectById(999L)).thenReturn(other);
            BusinessException ex2 = catchBusiness(() -> service.request(999L, dto(null, null)));
            assertThat(ex2.getErrorCode()).isEqualTo(ErrorCode.DEPOSIT_OUT_REQUEST_CONFLICT);
        }
    }

    @Test
    @DisplayName("⑤ 同号幂等：同租约同金额重复提交返回既有记录，不重复建单、不重复退款")
    void sameOutRequestNo_returnsExistingResult() {
        LeaseDepositRefund existing = existingRefund(new BigDecimal("6000.00"), DepositRefundStatus.SUCCESS);
        existing.setChannelRefundNo("2026092222001");
        existing.setRefundedAt(LocalDateTime.now());
        when(refundMapper.selectOne(any())).thenReturn(existing);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(new BigDecimal("6000.00"), null));

            assertThat(vo.getId()).isEqualTo(existing.getId());
            assertThat(vo.getStatus()).isEqualTo(DepositRefundStatus.SUCCESS);
            assertThat(vo.getChannelRefundNo()).isEqualTo("2026092222001");
            verify(refundMapper, never()).insert(any(LeaseDepositRefund.class));
            verify(alipayChannelClient, never()).refund(any(), anyString(), any(), anyString());
        }
    }

    @Test
    @DisplayName("⑥ 渠道凭据未配置（禁用）→ 5901，明示不静默失败")
    void channelDisabled_throws5901() {
        when(alipayChannelClient.enabled()).thenReturn(false);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            BusinessException ex = catchBusiness(() -> service.request(LEASE_ID, dto(null, null)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CHANNEL_DISABLED);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(5901);
            verify(refundMapper, never()).insert(any(LeaseDepositRefund.class));
        }
    }

    @Test
    @DisplayName("租约未搬出且无已通过退租申请 → 5602（未退租不得退押金）")
    void leaseNotMovedOut_throws5602() {
        lease.setStatus("ACTIVE");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            BusinessException ex = catchBusiness(() -> service.request(LEASE_ID, dto(null, null)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LEASE_STATUS_INVALID);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(5602);
        }
    }

    @Test
    @DisplayName("金额留空 = 全额退还（实缴押金 − 已成功退还金额），渠道成功 → REFUNDED + 渠道单号")
    void fullRefund_success() {
        when(alipayChannelClient.refund(any(), eq(OUT_REQUEST_NO), any(), anyString()))
                .thenReturn(ChannelRefundResult.success("2026092222001"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(null, null));

            /* 全额 = 实缴 6000（登记押金口径，存在成功首期单） */
            ArgumentCaptor<LeaseDepositRefund> captor = ArgumentCaptor.forClass(LeaseDepositRefund.class);
            verify(refundMapper).insert(captor.capture());
            assertThat(captor.getValue().getAmount()).isEqualByComparingTo("6000.00");
            assertThat(captor.getValue().getChannel()).isEqualTo(PaymentChannel.ALIPAY);
            assertThat(captor.getValue().getMoveOutApplicationId()).isNull();
            /* 库端 DEFAULT 不回填实体，insert 前显式置值避免响应里为 null */
            assertThat(captor.getValue().getCreatedAt()).isNotNull();

            assertThat(vo.getStatus()).isEqualTo(DepositRefundStatus.SUCCESS);
            assertThat(vo.getChannelRefundNo()).isEqualTo("2026092222001");
            assertThat(vo.getRefundedAt()).isNotNull();
            assertThat(vo.getOperatorId()).isEqualTo(OPERATOR_ID);

            /* 押金状态：事务一 REFUNDING → 事务二累计 6000 = 实缴 → REFUNDED */
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.REFUNDED);
            verify(refundMapper).updateById(any(LeaseDepositRefund.class));
            verify(changeLogService).logDepositRefund(eq(LEASE_ID), eq("发起押金退还"), anyString());
            verify(changeLogService).logDepositRefund(eq(LEASE_ID), eq("押金退还成功"), anyString());
            verify(notificationService).createForResident(eq(RESIDENT_ID), eq(COMMUNITY_ID),
                    eq("押金已退还"), anyString(), eq("LEASE"), eq("LEASE"), eq(LEASE_ID));
        }
    }

    @Test
    @DisplayName("渠道失败 → FAILED + fail_reason 可查，押金状态回落 PAID 可重试（换新请求号）")
    void channelFailure_rollsBackToPaid() {
        when(alipayChannelClient.refund(any(), anyString(), any(), anyString()))
                .thenReturn(ChannelRefundResult.failure("支付宝退款失败：交易不存在（ACQ.TRADE_NOT_EXIST）"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(new BigDecimal("6000.00"), null));

            assertThat(vo.getStatus()).isEqualTo(DepositRefundStatus.FAILED);
            assertThat(vo.getFailReason()).contains("ACQ.TRADE_NOT_EXIST");
            assertThat(vo.getRefundedAt()).isNull();
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.PAID);
            verify(changeLogService).logDepositRefund(eq(LEASE_ID), eq("押金退还失败"), anyString());
            verify(notificationService).createForResident(eq(RESIDENT_ID), eq(COMMUNITY_ID),
                    eq("押金退还失败"), anyString(), eq("LEASE"), eq("LEASE"), eq(LEASE_ID));
        }
    }

    @Test
    @DisplayName("渠道调用异常 → 不抛出、落 FAILED + 异常摘要（网络异常不打挂接口，可重试）")
    void channelException_settlesFailed() {
        when(alipayChannelClient.refund(any(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("connect timeout"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(null, null));

            assertThat(vo.getStatus()).isEqualTo(DepositRefundStatus.FAILED);
            assertThat(vo.getFailReason()).contains("connect timeout");
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.PAID);
        }
    }

    @Test
    @DisplayName("已成功退还金额从可退金额中扣减（部分退还后再全额申请 → 5704）")
    void alreadyRefundedAmount_isDeducted() {
        LeaseDepositRefund refunded = existingRefund(new BigDecimal("2500.00"), DepositRefundStatus.SUCCESS);
        when(refundMapper.selectList(any())).thenReturn(List.of(refunded));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            /* 可退 = 6000 − 2500 = 3500：申请 4000 拒绝 */
            BusinessException ex = catchBusiness(() ->
                    service.request(LEASE_ID, dto(new BigDecimal("4000.00"), null)));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPOSIT_AMOUNT_EXCEEDED);
            assertThat(ex.getMessage()).contains("3500.00");
        }
    }

    @Test
    @DisplayName("⑦ 居民越权：查看他人租约押金退还记录 403")
    void listByLease_otherResidentForbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            mocked.when(() -> SecurityUtils.hasRole(com.community.residence.common.constant.RoleConstants.RESIDENT))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.listByLease(LEASE_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("无权查看他人租约");
        }
    }

    @Test
    @DisplayName("⑧ 并发防线（DEF-103）：锁内重读到 REFUNDING → 5703，不落第二张 PENDING 单")
    void concurrentSecondRequest_readsRefundingInLock_throws5703() {
        /* 首个请求事务一已置 REFUNDING；并发第二请求（不同请求号）锁内重读即被门禁拦截 */
        lease.setDepositStatus(DepositStatus.REFUNDING);
        when(refundMapper.selectOne(any())).thenReturn(null);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            BusinessException ex = catchBusiness(() -> service.request(LEASE_ID, dto(null, null)));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPOSIT_STATUS_NOT_REFUNDABLE);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(5703);
            verify(refundMapper, never()).insert(any(LeaseDepositRefund.class));
            verify(alipayChannelClient, never()).refund(any(), anyString(), any(), anyString());
        }
    }

    @Test
    @DisplayName("⑨ 幂等键撞库（DEF-103）：insert 抛 DuplicateKeyException 后重查返回既有记录")
    void insertDuplicateKey_requeriesAndReturnsExisting() {
        LeaseDepositRefund existing = existingRefund(new BigDecimal("6000.00"), DepositRefundStatus.PENDING);
        /* 先查幂等为空 → insert 撞 uk_deposit_out_request_no → 重查命中既有 */
        when(refundMapper.selectOne(any())).thenReturn(null, existing);
        doAnswer(inv -> { throw new DuplicateKeyException("uk_deposit_out_request_no"); })
                .when(refundMapper).insert(any(LeaseDepositRefund.class));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(new BigDecimal("6000.00"), null));

            assertThat(vo.getOutRequestNo()).isEqualTo(OUT_REQUEST_NO);
            assertThat(vo.getId()).isEqualTo(existing.getId());
            verify(alipayChannelClient, never()).refund(any(), anyString(), any(), anyString());
        }
    }

    @Test
    @DisplayName("⑩ 部分退款（DEF-103）：实缴 3000 退 1000 成功 → 押金状态回 PAID（非 REFUNDED），余额可继续退")
    void partialRefund_success_keepsDepositPaid() {
        lease.setDeposit(new BigDecimal("3000.00"));
        when(alipayChannelClient.refund(any(), eq(OUT_REQUEST_NO), any(), anyString()))
                .thenReturn(ChannelRefundResult.success("2026092311001"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(new BigDecimal("1000.00"), null));

            assertThat(vo.getStatus()).isEqualTo(DepositRefundStatus.SUCCESS);
            /* 实缴口径 = 登记押金 3000；结算累计 1000 < 3000 → 保持 PAID 保留剩余额度 */
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.PAID);
        }
    }

    @Test
    @DisplayName("⑪ 累计口径（DEF-103）：已 SUCCESS 退 2000 + 再退 1000 = 实缴 3000 → 置 REFUNDED")
    void cumulativeRefund_reachesPaidDeposit_marksRefunded() {
        lease.setDeposit(new BigDecimal("3000.00"));
        LeaseDepositRefund prior = existingRefund(new BigDecimal("2000.00"), DepositRefundStatus.SUCCESS);
        when(refundMapper.selectList(any())).thenReturn(List.of(prior));
        when(alipayChannelClient.refund(any(), eq(OUT_REQUEST_NO), any(), anyString()))
                .thenReturn(ChannelRefundResult.success("2026092311002"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(new BigDecimal("1000.00"), null));

            assertThat(vo.getStatus()).isEqualTo(DepositRefundStatus.SUCCESS);
            /* 可退 = 3000 − 2000 = 1000；结算累计 2000 + 1000 = 3000 ≥ 实缴 → REFUNDED */
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.REFUNDED);
        }
    }

    @Test
    @DisplayName("⑫ 实缴口径（DEF-103 决策）：月租事后被编辑不影响实缴押金（按登记押金对照）")
    void paidDepositBasis_registeredDeposit_notRentDerivation() {
        /* 首期单 9000、登记押金 6000；月租事后被编辑为 5000——旧「单额−月租」口径
           推算实缴 4000 会误拒全额 6000，新口径按登记押金 6000 放行 */
        lease.setMonthlyRent(new BigDecimal("5000.00"));
        when(alipayChannelClient.refund(any(), eq(OUT_REQUEST_NO), any(), anyString()))
                .thenReturn(ChannelRefundResult.success("2026092311003"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);

            DepositRefundVO vo = service.request(LEASE_ID, dto(new BigDecimal("6000.00"), null));

            assertThat(vo.getStatus()).isEqualTo(DepositRefundStatus.SUCCESS);
            assertThat(lease.getDepositStatus()).isEqualTo(DepositStatus.REFUNDED);
        }
    }

    @Test
    @DisplayName("押金退还记录查询：本人租约按发起时间倒序返回，含失败原因")
    void listByLease_returnsRecords() {
        LeaseDepositRefund failed = existingRefund(new BigDecimal("6000.00"), DepositRefundStatus.FAILED);
        failed.setFailReason("渠道超时");
        when(refundMapper.selectList(any())).thenReturn(List.of(failed));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(com.community.residence.common.constant.RoleConstants.RESIDENT))
                    .thenReturn(true);

            List<DepositRefundVO> records = service.listByLease(LEASE_ID);

            assertThat(records).hasSize(1);
            assertThat(records.get(0).getStatus()).isEqualTo(DepositRefundStatus.FAILED);
            assertThat(records.get(0).getFailReason()).isEqualTo("渠道超时");
        }
    }

    private LeaseDepositRefund existingRefund(BigDecimal amount, String status) {
        LeaseDepositRefund refund = new LeaseDepositRefund();
        refund.setId(900L);
        refund.setLeaseId(LEASE_ID);
        refund.setAmount(amount);
        refund.setChannel(PaymentChannel.ALIPAY);
        refund.setOutRequestNo(OUT_REQUEST_NO);
        refund.setStatus(status);
        refund.setOperatorId(OPERATOR_ID);
        refund.setCreatedAt(LocalDateTime.now());
        return refund;
    }
}
