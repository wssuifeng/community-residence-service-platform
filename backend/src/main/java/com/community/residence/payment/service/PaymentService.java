package com.community.residence.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.constant.PaymentStatus;
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
import com.community.residence.payment.dto.CreateRenewPaymentDTO;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.payment.service.channel.ChannelTradeResult;
import com.community.residence.payment.service.channel.PaymentChannelClient;
import com.community.residence.payment.vo.LeasePaymentVO;
import com.community.residence.payment.vo.PaymentChannelVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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

    /** 业务单号前缀与日期段（RENEW+yyyyMMdd+6位随机，对齐工单号风格） */
    private static final String PAYMENT_NO_PREFIX = "RENEW";
    private static final DateTimeFormatter PAYMENT_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAYMENT_NO_RETRY_LIMIT = 5;

    private final LeasePaymentMapper paymentMapper;
    private final LeaseRecordMapper leaseMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final NotificationService notificationService;
    private final PaymentProperties paymentProperties;
    private final PaymentChannelClient alipayChannelClient;
    private final PaymentChannelClient wechatChannelClient;

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

        /* 幂等：同租约存在进行中支付单（PENDING）直接返回该单，不重复创建 */
        LeasePayment pending = paymentMapper.selectOne(new LambdaQueryWrapper<LeasePayment>()
                .eq(LeasePayment::getLeaseId, leaseId)
                .eq(LeasePayment::getStatus, PaymentStatus.PENDING)
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
        payment.setPaymentNo(generatePaymentNo());
        payment.setLeaseId(leaseId);
        payment.setResidentId(residentId);
        payment.setChannel(dto.getChannel());
        payment.setMonths(dto.getMonths());
        payment.setAmount(amount);
        payment.setStatus(PaymentStatus.PENDING);
        paymentMapper.insert(payment);

        String subject = "租约续租-" + dto.getMonths() + "个月";
        String credential;
        try {
            credential = client.createPayment(payment, subject, paymentProperties.getNotifyUrl());
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
            notificationService.create(current.getResidentId(), lease.getCommunityId(),
                    "续租支付成功",
                    "您的续租支付已确认到账，租期已延展至 " + newEndDate + "。",
                    "LEASE", "LEASE_PAYMENT", current.getId());
            for (Long adminId : boundAdminIds(lease.getCommunityId())) {
                notificationService.create(adminId, lease.getCommunityId(),
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
        return PageVO.of(result.convert(p -> toVO(p, null)));
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
                    "租约续租-" + payment.getMonths() + "个月", paymentProperties.getNotifyUrl());
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

    /** 支付单号：RENEW + yyyyMMdd + 6 位随机；撞唯一键循环重试（上限 5 次） */
    private String generatePaymentNo() {
        for (int i = 0; i < PAYMENT_NO_RETRY_LIMIT; i++) {
            String paymentNo = PAYMENT_NO_PREFIX + LocalDate.now().format(PAYMENT_NO_DATE)
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

    /* VO 装配：ALIPAY 凭据为跳转表单 → alipayForm，WECHAT 凭据为 code_url → qrCode */
    private LeasePaymentVO toVO(LeasePayment payment, String credential) {
        LeasePaymentVO vo = LeasePaymentVO.from(payment);
        if (credential != null && PaymentStatus.PENDING.equals(payment.getStatus())) {
            if (PaymentChannel.ALIPAY.equals(payment.getChannel())) {
                vo.setAlipayForm(credential);
            } else {
                vo.setQrCode(credential);
            }
        }
        return vo;
    }
}
