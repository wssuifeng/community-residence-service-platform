package com.community.residence.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.DepositStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.constant.PaymentStatus;
import com.community.residence.common.constant.PaymentType;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.payment.config.PaymentProperties;
import com.community.residence.payment.dto.CreateFirstPaymentDTO;
import com.community.residence.payment.dto.CreateRenewPaymentDTO;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.payment.service.channel.ChannelTradeResult;
import com.community.residence.payment.service.channel.PaymentChannelClient;
import com.community.residence.payment.vo.LeasePaymentVO;
import com.community.residence.payment.vo.PaymentChannelVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 租约续约支付业务逻辑（R61，需求规格 v1.4）。
 * 支付单状态机：PENDING → SUCCESS / CLOSED（终态）。
 * 支付结果以主动查单为准（本地无公网回调，决策日志 2026-09-20）：
 * 查到 SUCCESS 同一事务内回写支付单 + 延展租期（end_date 顺延 months 个月，
 * 沿用管理员 renew 的止期顺延口径）+ 通知居民与社区管理员；
 * 渠道异常吞掉保留 PENDING（网络不通不应打挂查询）。
 * 金额服务端按月租×月数计算，客户端不可传金额（防篡改）。
 */
@Slf4j
@Service
public class PaymentService {

    /** 业务单号前缀与日期段（RENEW/FIRST + yyyyMMdd + 6位随机，对齐工单号风格） */
    private static final String PAYMENT_NO_PREFIX = "RENEW";
    private static final String FIRST_PAYMENT_NO_PREFIX = "FIRST";
    private static final DateTimeFormatter PAYMENT_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAYMENT_NO_RETRY_LIMIT = 5;

    /** 首期支付固定月数（R71：首月租金 + 押金，与租约生效解耦） */
    private static final int FIRST_PAYMENT_MONTHS = 1;

    /** 首期支付渠道订单标题（与续期区分，便于对账） */
    private static final String FIRST_PAYMENT_SUBJECT = "租约首期费用（首月租金+押金）";

    private final LeasePaymentMapper paymentMapper;
    private final LeaseRecordMapper leaseMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final NotificationService notificationService;
    private final PaymentProperties paymentProperties;
    private final PaymentChannelClient alipayChannelClient;
    private final PaymentChannelClient wechatChannelClient;
    private RedissonClient redissonClient;

    public PaymentService(LeasePaymentMapper paymentMapper,
                          LeaseRecordMapper leaseMapper,
                          SysAdminCommunityMapper sysAdminCommunityMapper,
                          NotificationService notificationService,
                          PaymentProperties paymentProperties,
                          PaymentChannelClient alipayChannelClient,
                          PaymentChannelClient wechatChannelClient) {
        this.paymentMapper = paymentMapper;
        this.leaseMapper = leaseMapper;
        this.sysAdminCommunityMapper = sysAdminCommunityMapper;
        this.notificationService = notificationService;
        this.paymentProperties = paymentProperties;
        this.alipayChannelClient = alipayChannelClient;
        this.wechatChannelClient = wechatChannelClient;
    }

    /**
     * 首期支付并发锁客户端（DEF-107），setter 注入而非构造参数：
     * 既有单测（FirstPaymentDepositStatusTest / LeaseSigningClosureTest）按七参构造
     * 显式 new 本服务，本轮缺陷修复不扩改动文件面，构造签名保持不变；
     * 生产装配由 Spring 注入 RedissonClient，锁语义不受注入方式影响。
     */
    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 可用支付渠道（凭据门控：未配置的渠道 enabled=false 明示） */
    public List<PaymentChannelVO> channels() {
        return List.of(
                PaymentChannelVO.of(PaymentChannel.ALIPAY, alipayChannelClient.enabled()),
                PaymentChannelVO.of(PaymentChannel.WECHAT, wechatChannelClient.enabled()));
    }

    /**
     * 发起续约支付（RESIDENT）：租约须本人且 ACTIVE；幂等（本人已有 PENDING 单直接返回，
     * 同租约同期仅一单进行中——决策日志口径，应用层校验）；金额=月租×月数服务端计算；
     * 渠道凭据获取失败关单并抛「发起支付失败」。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE",
            targetType = "LEASE_PAYMENT", targetId = "#result.paymentNo",
            content = "'发起租约续约支付（' + #dto.channel + '，' + #dto.months + ' 个月）'")
    public LeasePaymentVO createRenewPayment(Long leaseId, CreateRenewPaymentDTO dto) {
        Long residentId = SecurityUtils.getUserId();
        LeaseRecord lease = leaseMapper.selectById(leaseId);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        if (!lease.getTenantId().equals(residentId)) {
            throw new ForbiddenException("无权对他人租约发起续约支付");
        }
        if (!LeaseStatus.ACTIVE.equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "仅已生效租约可续约，当前状态：" + lease.getStatus());
        }

        /* 幂等：同租约续期（含 V20 前存量单 payment_type 为空）存在进行中支付单直接返回该单 */
        LeasePayment pending = paymentMapper.selectOne(new LambdaQueryWrapper<LeasePayment>()
                .eq(LeasePayment::getLeaseId, leaseId)
                .eq(LeasePayment::getStatus, PaymentStatus.PENDING)
                .and(w -> w.eq(LeasePayment::getPaymentType, PaymentType.RENEWAL)
                        .or().isNull(LeasePayment::getPaymentType))
                .orderByDesc(LeasePayment::getId)
                .last("LIMIT 1"));
        if (pending != null) {
            return toVO(pending, credentialOf(pending));
        }

        PaymentChannelClient client = clientOf(dto.getChannel());
        if (!client.enabled()) {
            throw new BusinessException(ErrorCode.PAYMENT_CHANNEL_DISABLED);
        }

        /* 金额服务端权威计算（R61 验收：篡改拒绝——客户端无金额入参） */
        BigDecimal amount = lease.getMonthlyRent()
                .multiply(BigDecimal.valueOf(dto.getMonths()));

        LeasePayment payment = new LeasePayment();
        payment.setPaymentNo(generatePaymentNo(PAYMENT_NO_PREFIX));
        payment.setLeaseId(leaseId);
        payment.setResidentId(residentId);
        payment.setPaymentType(PaymentType.RENEWAL);
        payment.setChannel(dto.getChannel());
        payment.setMonths(dto.getMonths());
        payment.setAmount(amount);
        payment.setStatus(PaymentStatus.PENDING);
        paymentMapper.insert(payment);

        String credential;
        try {
            credential = client.createPayment(payment, subjectOf(payment), paymentProperties.getNotifyUrl());
        } catch (Exception e) {
            /* 凭据获取失败关单（PENDING→CLOSED）回滚创建意图，提示稍后重试 */
            payment.setStatus(PaymentStatus.CLOSED);
            paymentMapper.updateById(payment);
            log.error("发起支付失败已关单：paymentNo={}, channel={}", payment.getPaymentNo(),
                    payment.getChannel(), e);
            throw new BusinessException(ErrorCode.PAYMENT_CREATE_FAILED);
        }

        log.info("续约支付单已创建：paymentNo={}, leaseId={}, channel={}, months={}, amount={}, residentId={}",
                payment.getPaymentNo(), leaseId, payment.getChannel(), payment.getMonths(),
                payment.getAmount(), residentId);
        return toVO(payment, credential);
    }

    /**
     * 发起首期费用支付（R71，接口设计.md §9.19.12）：仅本人已生效租约；
     * `payment_type=FIRST`、`months=1`、金额 = 月租 + 押金（服务端计算，客户端无金额入参）；
     * **已到期租约不得支付首期（DEF-069 服务端兜底，前端门禁可绕过）**：止期严格早于当天即拒
     * `LEASE_EXPIRED(5606)`（当天到期不算已到期，口径见 {@link LeaseStatus#isExpired}）；
     * 续期支付 §9.13.2 不受本校验影响（到期正是续期场景）。
     * 幂等口径（DEF-068，与续期路径同）：已有**已完成（SUCCESS）**首期单 → 拒 5904
     * （首期只收一次，判定先于到期校验）；已有**进行中（PENDING）**首期单 → 原样返回该单并
     * 重新获取渠道凭据（居民手动关闭平台窗口后不再永久卡死）；**已关闭（CLOSED）或从未发起** →
     * 允许新建单。渠道凭据获取失败关单并抛「发起支付失败」。
     * **首期支付与租约生效解耦**：支付成功不顺延 end_date（见 settleSuccess 分支）。
     * 并发防护（DEF-107）：同租约发起经 Redisson 分布式锁 `lock:payment:first:{leaseId}`
     * 串行化——V15 lease_payment 自认无库级「同租约同期单」唯一约束，锁是防并发
     * 双 PENDING 双收款的唯一防线；方法级事务刻意不加（口径同
     * ViewingAppointmentService#insertAppointment：锁内单条 INSERT 自动提交，
     * 锁释放时后到请求立即可见，否则「解锁先于事务提交」重新打开竞态窗口）。
     */
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE",
            targetType = "LEASE_PAYMENT", targetId = "#result.paymentNo",
            content = "'发起首期费用支付（' + #dto.channel + '）'")
    public LeasePaymentVO createFirstPayment(Long leaseId, CreateFirstPaymentDTO dto) {
        Long residentId = SecurityUtils.getUserId();
        LeaseRecord lease = leaseMapper.selectById(leaseId);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        if (!lease.getTenantId().equals(residentId)) {
            throw new ForbiddenException("无权对他人租约发起首期费用支付");
        }
        if (!LeaseStatus.ACTIVE.equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID,
                    "仅已生效租约可支付首期费用，当前状态：" + lease.getStatus());
        }

        /* 锁内完成「已结清计数 + PENDING 续接 + insert」的 check-then-act 互斥（DEF-107）：
           并发双请求同读「无 SUCCESS 无 PENDING」会各建一单造成双收款；
           渠道远程调用在锁外（锁只保护单据判重临界区，不跨网络调用持锁） */
        RLock lock = redissonClient.getLock("lock:payment:first:" + leaseId);
        boolean locked = false;
        LeasePayment pending = null;
        LeasePayment payment = null;
        PaymentChannelClient client = null;
        try {
            try {
                locked = lock.tryLock(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "首期支付发起繁忙，请稍后重试");
            }
            if (!locked) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "首期支付发起繁忙，请稍后重试");
            }

            /* 首期只收一次（DEF-068）：已完成单优先判定——即使并发残留一张 PENDING 单，
               也不得再返回可支付单，避免已收款后又拉起收银台造成二次收款；
               该判定先于到期校验，故已到期租约上已收款的单仍以 5904 为准（不被 DEF-069 盖过） */
            Long settled = paymentMapper.selectCount(new LambdaQueryWrapper<LeasePayment>()
                    .eq(LeasePayment::getLeaseId, leaseId)
                    .eq(LeasePayment::getPaymentType, PaymentType.FIRST)
                    .eq(LeasePayment::getStatus, PaymentStatus.SUCCESS));
            if (settled != null && settled > 0) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_EXISTS);
            }

            /* 已到期租约不得再付首期（DEF-069 服务端兜底）：到期口径与 LeaseService 到期标注 /
               LeaseStatusTask / LeaseExpiryReminderTask 及前端 expiry-badge 一致——止期严格早于
               当天才算已到期（当天到期仍可支付，属「即将到期」窗口内）；已到期租约即便留有
               PENDING 首期单也不续接，居民应改走续租或退租（续期路径 §9.13.2 不受本校验约束） */
            if (LeaseStatus.isExpired(lease.getEndDate(), LocalDate.now())) {
                throw new BusinessException(ErrorCode.LEASE_EXPIRED,
                        "租约已于 " + lease.getEndDate() + " 到期，不可支付首期费用，请先续租或办理退租");
            }

            /* 幂等续接（DEF-068）：同租约已存在进行中首期单则原样返回该单并补回渠道凭据
               （口径同 createRenewPayment；CLOSED 不在判定范围，故放弃后可重新发起新单） */
            pending = paymentMapper.selectOne(new LambdaQueryWrapper<LeasePayment>()
                    .eq(LeasePayment::getLeaseId, leaseId)
                    .eq(LeasePayment::getPaymentType, PaymentType.FIRST)
                    .eq(LeasePayment::getStatus, PaymentStatus.PENDING)
                    .orderByDesc(LeasePayment::getId)
                    .last("LIMIT 1"));
            if (pending == null) {
                client = clientOf(dto.getChannel());
                if (!client.enabled()) {
                    throw new BusinessException(ErrorCode.PAYMENT_CHANNEL_DISABLED);
                }

                /* 金额服务端权威计算：首月租金 + 押金（月租/押金均缺省按 0 计，
                   防御租约属性缺失时的 NPE，对齐 fillAmountBreakdown 的写法，DEF-128） */
                BigDecimal amount = (lease.getMonthlyRent() != null ? lease.getMonthlyRent() : BigDecimal.ZERO)
                        .add(lease.getDeposit() != null ? lease.getDeposit() : BigDecimal.ZERO);

                payment = new LeasePayment();
                payment.setPaymentNo(generatePaymentNo(FIRST_PAYMENT_NO_PREFIX));
                payment.setLeaseId(leaseId);
                payment.setResidentId(residentId);
                payment.setPaymentType(PaymentType.FIRST);
                payment.setChannel(dto.getChannel());
                payment.setMonths(FIRST_PAYMENT_MONTHS);
                payment.setAmount(amount);
                payment.setStatus(PaymentStatus.PENDING);
                paymentMapper.insert(payment);
            }
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
        }

        if (pending != null) {
            return toVO(pending, credentialOf(pending));
        }
        String credential;
        try {
            credential = client.createPayment(payment, firstPaymentSubject(), paymentProperties.getNotifyUrl());
        } catch (Exception e) {
            /* 凭据获取失败关单（PENDING→CLOSED）留痕，阻断该单再次拉起收银台，提示稍后重试 */
            payment.setStatus(PaymentStatus.CLOSED);
            paymentMapper.updateById(payment);
            log.error("发起首期支付失败已关单：paymentNo={}, channel={}", payment.getPaymentNo(),
                    payment.getChannel(), e);
            throw new BusinessException(ErrorCode.PAYMENT_CREATE_FAILED);
        }

        log.info("首期支付单已创建：paymentNo={}, leaseId={}, channel={}, amount={}, residentId={}",
                payment.getPaymentNo(), leaseId, payment.getChannel(), payment.getAmount(), residentId);
        return toVO(payment, credential);
    }

    /**
     * 某租约的支付单列表（R71 契约补充）：居民限本人租约（非本人 403），
     * ADMIN/SUPER_ADMIN 按社区数据权限可查（非绑定社区 403）；按创建时间倒序，
     * 含 paymentType 供前端区分「首期 / 续期」。
     */
    public List<LeasePaymentVO> listByLease(Long leaseId) {
        LeaseRecord lease = leaseMapper.selectById(leaseId);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !lease.getTenantId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人租约支付单");
        }
        if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        }
        return paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>()
                        .eq(LeasePayment::getLeaseId, leaseId)
                        .orderByDesc(LeasePayment::getId))
                .stream().map(p -> toVO(p, null, lease)).toList();
    }

    /**
     * 查询支付单（本人或管理员）：PENDING 时主动向渠道查单——
     * 查到 SUCCESS 同一事务回写支付单 + 续约落账（延展租期 + 双方通知）；
     * 渠道异常吞掉保留 PENDING；已 SUCCESS 幂等（不重复延展）。
     */
    public LeasePaymentVO getStatus(String paymentNo) {
        LeasePayment payment = requirePayment(paymentNo);
        checkReadAccess(payment);
        if (PaymentStatus.PENDING.equals(payment.getStatus())) {
            queryAndSettle(payment);
        }
        return toVO(payment, null);
    }

    /** 主动查单并落账：渠道异常 WARN 吞掉（保留 PENDING）；成功则回写+延展+通知（同一事务） */
    @Transactional(rollbackFor = Exception.class)
    public void queryAndSettle(LeasePayment payment) {
        PaymentChannelClient client = clientOf(payment.getChannel());
        ChannelTradeResult result;
        try {
            result = client.queryTrade(payment.getPaymentNo());
        } catch (Exception e) {
            /* 网络不通不应打挂查询：保留 PENDING，前端轮询稍后重试 */
            log.warn("渠道查单异常，保留待支付状态：paymentNo={}, channel={}",
                    payment.getPaymentNo(), payment.getChannel(), e);
            return;
        }
        if (result == null) {
            return;
        }
        settleSuccess(payment, result);
    }

    /** 支付成功落账：回写支付单 + 延展租期 + 双方通知（同一事务，已 SUCCESS 不重复延展） */
    @Transactional(rollbackFor = Exception.class)
    protected void settleSuccess(LeasePayment payment, ChannelTradeResult result) {
        /* 幂等重入保护：并发轮询下前一笔事务已落账则跳过 */
        LeasePayment current = paymentMapper.selectById(payment.getId());
        if (current == null || !PaymentStatus.PENDING.equals(current.getStatus())) {
            return;
        }
        LeaseRecord lease = leaseMapper.selectById(current.getLeaseId());
        if (lease == null) {
            log.error("支付单对应租约不存在，仅回写支付状态：paymentNo={}, leaseId={}",
                    current.getPaymentNo(), current.getLeaseId());
        } else if (PaymentType.FIRST.equals(current.getPaymentType())) {
            /* 首期落账（R71）：只回写支付状态与通知，**不顺延 end_date**
               ——首期费用与租约生效解耦，租期由签约/续租动作决定 */
            log.info("首期费用支付成功（租期不变）：paymentNo={}, leaseId={}, amount={}",
                    current.getPaymentNo(), lease.getId(), current.getAmount());
            /* 押金状态联动（R72）：首期费用中的押金部分入账 → 已缴（deposit_status=PAID）；
               押金为 0 的租约不置已缴，避免出现「可退还 0 元」的误导状态 */
            BigDecimal depositPart = depositPartOf(current, lease);
            if (depositPart.signum() > 0) {
                lease.setDepositStatus(DepositStatus.PAID);
                leaseMapper.updateById(lease);
                log.info("租约押金状态置为已缴：leaseId={}, depositPart={}", lease.getId(), depositPart);
            }
            notificationService.createForResident(current.getResidentId(), lease.getCommunityId(),
                    "首期费用支付成功",
                    "您的首期费用（首月租金 + 押金，共 " + current.getAmount() + " 元）已确认到账。",
                    "LEASE", "LEASE_PAYMENT", current.getId());
            for (Long adminId : boundAdminIds(lease.getCommunityId())) {
                notificationService.createForUser(adminId, lease.getCommunityId(),
                        "社区租约首期费用到账",
                        "租住记录 #" + lease.getId() + " 的首期费用已到账（"
                                + current.getAmount() + " 元）。",
                        "LEASE", "LEASE_PAYMENT", current.getId());
            }
        } else {
            /* 续约落账：end_date 顺延 months 个月（沿用管理员 renew 止期顺延口径）；
               已过期租约从当天起顺延（支付完成时租约已过期的合理续期语义） */
            LocalDate base = lease.getEndDate().isBefore(LocalDate.now())
                    ? LocalDate.now() : lease.getEndDate();
            LocalDate newEndDate = base.plusMonths(current.getMonths());
            lease.setEndDate(newEndDate);
            leaseMapper.updateById(lease);
            log.info("续约支付成功租期延展：paymentNo={}, leaseId={}, {} -> {}",
                    current.getPaymentNo(), lease.getId(), base, newEndDate);

            /* 双方通知（C11）：居民 + 该社区绑定管理员（参考到期提醒任务口径） */
            notificationService.createForResident(current.getResidentId(), lease.getCommunityId(),
                    "续租支付成功",
                    "您的续租支付已确认到账，租期已延展至 " + newEndDate + "。",
                    "LEASE", "LEASE_PAYMENT", current.getId());
            for (Long adminId : boundAdminIds(lease.getCommunityId())) {
                notificationService.createForUser(adminId, lease.getCommunityId(),
                        "社区租约续约成功",
                        "租住记录 #" + lease.getId() + " 的续租支付已到账（"
                                + current.getAmount() + " 元，" + current.getMonths() + " 个月），"
                                + "租期已延展至 " + newEndDate + "。",
                        "LEASE", "LEASE_PAYMENT", current.getId());
            }
        }

        current.setStatus(PaymentStatus.SUCCESS);
        current.setChannelTradeNo(result.channelTradeNo());
        current.setPaidAt(result.paidAt() != null ? result.paidAt() : LocalDateTime.now());
        paymentMapper.updateById(current);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setChannelTradeNo(result.channelTradeNo());
        payment.setPaidAt(current.getPaidAt());
    }

    /** 关闭支付单（本人放弃支付）：仅 PENDING 可关（SUCCESS 已落账不可关，CLOSED 幂等） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS",
            targetType = "LEASE_PAYMENT", targetId = "#paymentNo", content = "'关闭续约支付单'")
    public void closePayment(String paymentNo) {
        LeasePayment payment = requirePayment(paymentNo);
        if (!payment.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权操作他人支付单");
        }
        if (PaymentStatus.SUCCESS.equals(payment.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已支付的单不可关闭");
        }
        if (PaymentStatus.CLOSED.equals(payment.getStatus())) {
            return;
        }
        payment.setStatus(PaymentStatus.CLOSED);
        paymentMapper.updateById(payment);
        log.info("支付单已关闭（居民放弃支付）：paymentNo={}, operator={}",
                paymentNo, SecurityUtils.getUserId());
    }

    /** 本人支付单列表（居民视角，可选渠道过滤） */
    public PageVO<LeasePaymentVO> page(long page, long size, String channel) {
        LambdaQueryWrapper<LeasePayment> wrapper = new LambdaQueryWrapper<LeasePayment>()
                .eq(LeasePayment::getResidentId, SecurityUtils.getUserId())
                .eq(StringUtils.hasText(channel), LeasePayment::getChannel, channel)
                .orderByDesc(LeasePayment::getId);
        Page<LeasePayment> result = paymentMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        /* 金额构成/覆盖租期按租约登记值装配（R73）：按 distinct leaseId 一次 selectBatchIds
           批量装载，替代 convert 内逐行 selectById 的 N+1（DEF-127，OCR 支付分页部分） */
        List<Long> leaseIds = result.getRecords().stream()
                .map(LeasePayment::getLeaseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, LeaseRecord> leaseMap = leaseIds.isEmpty() ? Map.of()
                : leaseMapper.selectBatchIds(leaseIds).stream()
                        .collect(Collectors.toMap(LeaseRecord::getId, Function.identity()));
        return PageVO.of(result.convert(p -> toVO(p, null, leaseMap.get(p.getLeaseId()))));
    }

    /* 渠道客户端分发 */
    private PaymentChannelClient clientOf(String channel) {
        if (PaymentChannel.WECHAT.equals(channel)) {
            return wechatChannelClient;
        }
        return alipayChannelClient;
    }

    /* PENDING 单查询时补回支付凭据（幂等返回/继续支付场景前端需要凭据重新拉起收银台） */
    private String credentialOf(LeasePayment payment) {
        if (!PaymentStatus.PENDING.equals(payment.getStatus())) {
            return null;
        }
        try {
            return clientOf(payment.getChannel()).createPayment(payment,
                    subjectOf(payment), paymentProperties.getNotifyUrl());
        } catch (Exception e) {
            /* 凭据重新获取失败不阻断查询：返回单据信息，居民可关单重开 */
            log.warn("幂等返回时重新获取支付凭据失败：paymentNo={}", payment.getPaymentNo(), e);
            return null;
        }
    }

    private LeasePayment requirePayment(String paymentNo) {
        LeasePayment payment = paymentMapper.selectOne(new LambdaQueryWrapper<LeasePayment>()
                .eq(LeasePayment::getPaymentNo, paymentNo));
        if (payment == null) {
            throw new ResourceNotFoundException("支付单不存在");
        }
        return payment;
    }

    /* 读权限：RESIDENT 限本人；ADMIN/SUPER_ADMIN 可查（管理端查续约与支付记录，R61） */
    private void checkReadAccess(LeasePayment payment) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !payment.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人支付单");
        }
    }

    /* 社区绑定管理员 ID 集合（通知口径同到期提醒任务） */
    private List<Long> boundAdminIds(Long communityId) {
        return sysAdminCommunityMapper.selectList(new LambdaQueryWrapper<SysAdminCommunity>()
                        .eq(SysAdminCommunity::getCommunityId, communityId))
                .stream().map(SysAdminCommunity::getAdminId).toList();
    }

    /** 支付单号：前缀 + yyyyMMdd + 6 位随机；撞唯一键循环重试（上限 5 次） */
    private String generatePaymentNo(String prefix) {
        for (int i = 0; i < PAYMENT_NO_RETRY_LIMIT; i++) {
            String paymentNo = prefix + LocalDate.now().format(PAYMENT_NO_DATE)
                    + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
            Long exists = paymentMapper.selectCount(new LambdaQueryWrapper<LeasePayment>()
                    .eq(LeasePayment::getPaymentNo, paymentNo));
            if (exists == 0) {
                return paymentNo;
            }
            log.warn("支付单号撞号重试（{}/{}）：{}", i + 1, PAYMENT_NO_RETRY_LIMIT, paymentNo);
        }
        throw new BusinessException(ErrorCode.OPERATION_FAILED, "支付单号生成失败，请稍后重试");
    }

    /* 渠道订单标题：首期与续期文案不同，居民收银台可见；存量单类型为空按续期口径 */
    private String subjectOf(LeasePayment payment) {
        if (PaymentType.FIRST.equals(payment.getPaymentType())) {
            return FIRST_PAYMENT_SUBJECT;
        }
        return "租约续租-" + payment.getMonths() + "个月";
    }

    private String firstPaymentSubject() {
        return FIRST_PAYMENT_SUBJECT;
    }

    /* VO 装配：ALIPAY 凭据为跳转表单 → alipayForm，WECHAT 凭据为 code_url → qrCode；
       并补齐 R73 金额构成与覆盖租期（需读租约登记值，故按租约装配一次） */
    private LeasePaymentVO toVO(LeasePayment payment, String credential) {
        LeaseRecord lease = payment.getLeaseId() != null ? leaseMapper.selectById(payment.getLeaseId()) : null;
        return toVO(payment, credential, lease);
    }

    private LeasePaymentVO toVO(LeasePayment payment, String credential, LeaseRecord lease) {
        LeasePaymentVO vo = LeasePaymentVO.from(payment);
        if (credential != null && PaymentStatus.PENDING.equals(payment.getStatus())) {
            if (PaymentChannel.ALIPAY.equals(payment.getChannel())) {
                vo.setAlipayForm(credential);
            } else {
                vo.setQrCode(credential);
            }
        }
        fillAmountBreakdown(vo, payment, lease);
        return vo;
    }

    /**
     * R73 金额构成与覆盖租期（服务端保证 amount = rentAmount + depositAmount）：
     * 首期 = 月租（租金部分）+ 押金（押金部分），覆盖首月；续期 = 月租×月数（全为租金部分），
     * 覆盖「止期前推 months 个月 ~ 当前止期」。
     * 押金部分取「单额 − 租金部分」（租金部分不超过单额），避免租约属性后续被编辑后拆分失真。
     */
    private void fillAmountBreakdown(LeasePaymentVO vo, LeasePayment payment, LeaseRecord lease) {
        BigDecimal amount = payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO;
        if (PaymentType.FIRST.equals(payment.getPaymentType())) {
            BigDecimal rent = lease != null && lease.getMonthlyRent() != null
                    ? lease.getMonthlyRent() : BigDecimal.ZERO;
            BigDecimal rentPart = rent.compareTo(amount) > 0 ? amount : rent;
            vo.setRentAmount(rentPart);
            vo.setDepositAmount(amount.subtract(rentPart));
            if (lease != null && lease.getStartDate() != null) {
                vo.setPeriodStart(lease.getStartDate());
                vo.setPeriodEnd(lease.getStartDate().plusMonths(1));
            }
            return;
        }
        vo.setRentAmount(amount);
        vo.setDepositAmount(BigDecimal.ZERO);
        if (lease != null && lease.getEndDate() != null) {
            int months = payment.getMonths() != null && payment.getMonths() > 0 ? payment.getMonths() : 0;
            vo.setPeriodEnd(lease.getEndDate());
            vo.setPeriodStart(lease.getEndDate().minusMonths(months));
        }
    }

    /* 单笔支付中的押金部分（首期 = 单额 − 月租，其余 = 0）：与押金退还的可退金额口径一致 */
    private BigDecimal depositPartOf(LeasePayment payment, LeaseRecord lease) {
        if (!PaymentType.FIRST.equals(payment.getPaymentType())) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO;
        BigDecimal rent = lease.getMonthlyRent() != null ? lease.getMonthlyRent() : BigDecimal.ZERO;
        BigDecimal part = amount.subtract(rent);
        return part.signum() > 0 ? part : BigDecimal.ZERO;
    }

    /* 锁释放吞异常（DEF-107）：锁键过期/看门狗续期竞态属正常场景，不打挂业务返回
       （口径同 ViewingAppointmentService#unlockQuietly） */
    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (Exception e) {
            log.warn("首期支付锁释放异常（锁键已过期时正常）", e);
        }
    }
}
