package com.community.residence.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.DepositRefundStatus;
import com.community.residence.common.constant.DepositStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.common.constant.MoveOutStatus;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.constant.PaymentStatus;
import com.community.residence.common.constant.PaymentType;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.lease.dto.CreateDepositRefundDTO;
import com.community.residence.lease.entity.LeaseDepositRefund;
import com.community.residence.lease.entity.LeaseMoveOutApplication;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 押金退还业务逻辑（R72，接口设计 §9.20；定义 v1.6 §3 边界）。
 *
 * 硬约束（三条，均在应用层显式守住）：
 * ① **退款金额 ≤ 实缴押金**——实缴口径（DEF-103，2026-09-23 决策日志）：存在 SUCCESS
 *    首期单即视为押金已实缴，实缴额以租约登记押金 lease_record.deposit 为对照基准；
 *    累计成功退款 ≥ 实缴才置 REFUNDED，部分退款成功保持 PAID（剩余额度可再次发起），超限 5704；
 * ② **必须原渠道原路退回**——渠道取实缴支付单的渠道，不接受客户端指定（传入不一致即 5705）；
 * ③ **不托管资金**——本服务只发起渠道退款指令并留痕（lease_deposit_refund 追加型记录），
 *    库内不出现任何押金资金账户 / 余额 / 计息字段（系统定义 v1.6 §3，架构核查项）。
 *
 * 并发与事务边界（DEF-103）：request() 以 Redisson 分布式锁（lock:deposit:refund:{leaseId}）
 * 串行化同租约的退还发起，锁内完成租约重读、校验与幂等——两个并发请求不可能都读到 PAID
 * 各退一次造成累计超退；渠道远程调用移出数据库事务，拆为三段：事务一落 PENDING 单 +
 * 置 REFUNDING + 变更留痕（原子提交，门禁态与单据同进同退）→ 事务外渠道调用（不占数据库
 * 连接，渠道结果只落不回滚——渠道成功而本地结算失败时 PENDING/REFUNDING 保留，同号重试
 * 幂等返回，out_request_no 同时是渠道幂等键，不会二次扣款）→ 事务二按渠道结果结算。
 *
 * 状态联动：发起 → 退款单 PENDING + 租约押金状态 REFUNDING；渠道成功 → SUCCESS +
 * （累计达实缴）REFUNDED /（部分退款）PAID；渠道失败/异常 → FAILED + fail_reason，
 * 押金状态回落 PAID（可换新请求号重试）。
 * 幂等：out_request_no 唯一（库级 uk + 应用层先查 + insert 撞 DuplicateKeyException 重查兜底），
 * 同号重复提交返回既有记录，同号但租约/金额不一致返回 5706。
 */
@Slf4j
@Service
public class DepositRefundService {

    /** 渠道失败原因落库长度上限（lease_deposit_refund.fail_reason VARCHAR(500)） */
    private static final int FAIL_REASON_MAX_LENGTH = 500;

    private final LeaseDepositRefundMapper refundMapper;
    private final LeaseRecordMapper leaseMapper;
    private final LeasePaymentMapper paymentMapper;
    private final LeaseMoveOutApplicationMapper moveOutApplicationMapper;
    private final LeaseChangeLogService changeLogService;
    private final NotificationService notificationService;
    private final SysUserMapper sysUserMapper;
    private final PaymentChannelClient alipayChannelClient;
    private final PaymentChannelClient wechatChannelClient;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    /* 渠道客户端显式构造注入：alipay/wechat 为同接口实例，按参数名（bean 名）区分；
       RedissonClient 承载并发锁，TransactionTemplate 承载三段式事务边界（DEF-103） */
    public DepositRefundService(LeaseDepositRefundMapper refundMapper,
                                LeaseRecordMapper leaseMapper,
                                LeasePaymentMapper paymentMapper,
                                LeaseMoveOutApplicationMapper moveOutApplicationMapper,
                                LeaseChangeLogService changeLogService,
                                NotificationService notificationService,
                                SysUserMapper sysUserMapper,
                                PaymentChannelClient alipayChannelClient,
                                PaymentChannelClient wechatChannelClient,
                                RedissonClient redissonClient,
                                TransactionTemplate transactionTemplate) {
        this.refundMapper = refundMapper;
        this.leaseMapper = leaseMapper;
        this.paymentMapper = paymentMapper;
        this.moveOutApplicationMapper = moveOutApplicationMapper;
        this.changeLogService = changeLogService;
        this.notificationService = notificationService;
        this.sysUserMapper = sysUserMapper;
        this.alipayChannelClient = alipayChannelClient;
        this.wechatChannelClient = wechatChannelClient;
        this.redissonClient = redissonClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 发起押金退还（管理端）：分布式锁内校验，顺序（硬口径，幂等优先）——
     * 租约存在 → 数据权限（非绑定社区 403）→ **幂等命中直接返回既有结果（同号内容不一致 5706）**
     * → 租约状态（须已搬出或存在已通过退租申请，否则 5602）→ 押金状态（仅 PAID，否则 5703；
     * 并发第二请求锁内重读到 REFUNDING 在此拦截，不再落第二张 PENDING 单）
     * → 金额（≤ 可退金额，否则 5704）→ 渠道一致性（否则 5705）→ 凭据门控（否则 5901）
     * → 事务一落 PENDING 单 + 押金状态 REFUNDING → 事务外调渠道 → 事务二落 SUCCESS / FAILED。
     */
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE",
            targetType = "LEASE_DEPOSIT_REFUND", targetId = "#leaseId",
            content = "'发起押金退还：请求号 ' + #dto.outRequestNo")
    public DepositRefundVO request(Long leaseId, CreateDepositRefundDTO dto) {
        RLock lock = redissonClient.getLock("lock:deposit:refund:" + leaseId);
        boolean locked = false;
        try {
            try {
                locked = lock.tryLock(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.OPERATION_FAILED, "押金退还请求繁忙，请稍后重试");
            }
            if (!locked) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED, "押金退还正在处理中，请稍后重试");
            }

            /* —— 校验段（锁内、无事务读）：租约重读 → 幂等 → 租约前置 → 押金状态门禁 → 金额 → 渠道 —— */

            /* 租约锁内重读（DEF-103）：锁前快照可能携带并发前的 PAID 旧值，
               门禁必须以持锁时刻的库内状态为准 */
            LeaseRecord lease = requireLease(leaseId);
            SecurityUtils.checkCommunityAccess(lease.getCommunityId());

            /* 幂等优先：同号已存在 → 租约与金额一致即返回既有结果（重复提交不重复退款）；
               内容不一致说明请求号被复用，返回 5706（唯一键 uk_deposit_out_request_no 库级兜底） */
            LeaseDepositRefund existing = refundMapper.selectOne(new LambdaQueryWrapper<LeaseDepositRefund>()
                    .eq(LeaseDepositRefund::getOutRequestNo, dto.getOutRequestNo())
                    .last("LIMIT 1"));
            if (existing != null) {
                return existingOrConflict(existing, leaseId, dto);
            }

            /* 同一批数据锁内各查一次，供后续门禁与落单复用（DEF-103 冗余查询折叠） */
            List<LeasePayment> paidList = paidFirstPayments(leaseId);
            BigDecimal paidDeposit = paidDeposit(lease, paidList);
            BigDecimal refunded = refundedAmount(leaseId);
            LeaseMoveOutApplication approvedMoveOut = approvedMoveOut(leaseId);

            /* 前置：租约须已退租结算（MOVED_OUT 或有已通过的退租申请），否则不允许退押金 */
            if (!LeaseStatus.MOVED_OUT.equals(lease.getStatus()) && approvedMoveOut == null) {
                throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID,
                        "仅已办理搬出（或退租申请已通过）的租约可退还押金，当前租约状态：" + lease.getStatus());
            }

            /* 押金状态门禁：仅「已缴」可发起（未缴 / 退还中 / 已退还 / 已抵扣 一律拒绝） */
            if (!DepositStatus.PAID.equals(currentDepositStatus(lease))) {
                throw new BusinessException(ErrorCode.DEPOSIT_STATUS_NOT_REFUNDABLE,
                        "押金当前状态不允许退还：" + currentDepositStatus(lease));
            }

            /* 金额：可退金额 = 实缴押金（登记押金口径，存在成功首期单即实缴）− 已成功退还金额 */
            BigDecimal refundable = paidDeposit.subtract(refunded);
            BigDecimal amount = dto.getAmount() != null ? dto.getAmount() : refundable;
            if (amount.signum() <= 0 || amount.compareTo(refundable) > 0) {
                throw new BusinessException(ErrorCode.DEPOSIT_AMOUNT_EXCEEDED,
                        "退款金额超出可退押金（可退 " + refundable.toPlainString() + " 元）");
            }

            /* 原渠道原路退回：渠道只能取实缴支付单的渠道，客户端传入不一致即拒（不允许跨渠道退款） */
            LeasePayment paidPayment = paidList.get(0);
            String channel = paidPayment.getChannel();
            if (!StringUtils.hasText(channel)) {
                /* 数据异常防线（DEF-103，保留分支防回归）：实缴为 0 已被上方金额门禁 5704 拦截，
                   到此仅剩「首期单渠道字段缺失」的脏数据——留痕并拒绝，不允许无渠道退款 */
                log.warn("实缴首期单渠道信息缺失，无法原路退回：leaseId={}, paymentId={}",
                        leaseId, paidPayment.getId());
                throw new BusinessException(ErrorCode.DEPOSIT_AMOUNT_EXCEEDED, "未查到实缴押金记录，无可退金额");
            }
            if (StringUtils.hasText(dto.getChannel()) && !channel.equals(dto.getChannel())) {
                throw new BusinessException(ErrorCode.DEPOSIT_CHANNEL_MISMATCH,
                        "退款须原渠道退回（实缴渠道 " + channel + "），不支持跨渠道退款");
            }
            PaymentChannelClient client = clientOf(channel);
            if (!client.enabled()) {
                /* 凭据门控：渠道禁用即明示，不静默失败（与发起支付同口径） */
                throw new BusinessException(ErrorCode.PAYMENT_CHANNEL_DISABLED,
                        "该支付渠道未配置（凭据缺失），暂不可发起退款：" + channel);
            }

            /* —— 事务一：落 PENDING 单 + 置 REFUNDING + 留痕（原子提交，DEF-103）——
               insert 撞 uk_deposit_out_request_no 时整段回滚，重查命中即幂等返回 */
            LeaseDepositRefund refund = new LeaseDepositRefund();
            refund.setLeaseId(leaseId);
            refund.setMoveOutApplicationId(approvedMoveOut != null ? approvedMoveOut.getId() : null);
            refund.setAmount(amount);
            refund.setChannel(channel);
            refund.setOutRequestNo(dto.getOutRequestNo());
            refund.setStatus(DepositRefundStatus.PENDING);
            refund.setOperatorId(SecurityUtils.getUserId());
            refund.setCreatedAt(LocalDateTime.now());
            String changeDetail = "金额 " + amount.toPlainString() + " 元，渠道 " + channel
                    + "，请求号 " + dto.getOutRequestNo()
                    + (StringUtils.hasText(dto.getRemark()) ? "；备注：" + dto.getRemark() : "");
            try {
                transactionTemplate.executeWithoutResult(s -> {
                    refundMapper.insert(refund);
                    lease.setDepositStatus(DepositStatus.REFUNDING);
                    leaseMapper.updateById(lease);
                    changeLogService.logDepositRefund(leaseId, "发起押金退还", boundedChangeDetail(changeDetail));
                });
            } catch (DuplicateKeyException e) {
                /* 撞库重查（DEF-103）：先查与 insert 之间并发落库的同号记录，胜者即既有记录 */
                LeaseDepositRefund winner = refundMapper.selectOne(new LambdaQueryWrapper<LeaseDepositRefund>()
                        .eq(LeaseDepositRefund::getOutRequestNo, dto.getOutRequestNo())
                        .last("LIMIT 1"));
                if (winner != null) {
                    return existingOrConflict(winner, leaseId, dto);
                }
                throw e;
            }

            /* —— 事务外：渠道调用（DEF-103）——不占数据库连接；失败仅落 FAILED，
               不回滚事务一的 PENDING 与 REFUNDING 前置（同号重试幂等、渠道幂等键防二次扣款） */
            ChannelRefundResult result;
            try {
                result = client.refund(paidPayment, dto.getOutRequestNo(), amount,
                        StringUtils.hasText(dto.getRemark()) ? dto.getRemark() : "租约押金退还");
            } catch (Exception e) {
                log.error("押金退还渠道调用异常：leaseId={}, outRequestNo={}", leaseId, dto.getOutRequestNo(), e);
                result = ChannelRefundResult.failure("渠道调用异常：" + e.getMessage());
            }

            /* —— 事务二：按渠道结果结算 —— */
            return settle(lease, refund, result, dto.getRemark());
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
        }
    }

    /**
     * 某租约的押金退还记录（R72 §9.20.2）：RESIDENT 限本人租约（越权 403），
     * 其余角色走社区归属校验；按发起时间倒序，不分页（单租约记录量小）。
     */
    public List<DepositRefundVO> listByLease(Long leaseId) {
        LeaseRecord lease = requireLease(leaseId);
        /* 居民侧唯一数据防线（DEF-103）：lease_deposit_refund 在数据级拦截器 SKIP_TABLES 中，
           拦截器不注入社区条件——非本人租约必须在此显式拒绝，勿删 */
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            if (!lease.getTenantId().equals(SecurityUtils.getUserId())) {
                throw new ForbiddenException("无权查看他人租约的押金退还记录");
            }
        } else {
            SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        }
        return refundMapper.selectList(new LambdaQueryWrapper<LeaseDepositRefund>()
                        .eq(LeaseDepositRefund::getLeaseId, leaseId)
                        .orderByDesc(LeaseDepositRefund::getCreatedAt)
                        .orderByDesc(LeaseDepositRefund::getId))
                .stream().map(this::toVO).toList();
    }

    /* 渠道落账（事务二，DEF-103）：成功 → SUCCESS + 累计口径动押金状态（累计成功退款
       ≥ 实缴才 REFUNDED，部分退款成功回 PAID 保留剩余额度）；失败 → FAILED + 原因 +
       押金状态回落已缴（可重试） */
    private DepositRefundVO settle(LeaseRecord lease, LeaseDepositRefund refund,
                                   ChannelRefundResult result, String remark) {
        if (result != null && result.success()) {
            transactionTemplate.executeWithoutResult(s -> {
                /* 累计口径：先取既有 SUCCESS 合计（本单此刻仍 PENDING 未计入）再加本单，
                   与回写后再查会造成同单双计——顺序不可调换 */
                BigDecimal refundedTotal = refundedAmount(lease.getId()).add(refund.getAmount());
                boolean fullyRefunded = paidDeposit(lease, paidFirstPayments(lease.getId()))
                        .compareTo(refundedTotal) <= 0;

                LocalDateTime now = LocalDateTime.now();
                refund.setStatus(DepositRefundStatus.SUCCESS);
                refund.setChannelRefundNo(result.channelRefundNo());
                refund.setRefundedAt(now);
                refundMapper.updateById(refund);

                lease.setDepositStatus(fullyRefunded ? DepositStatus.REFUNDED : DepositStatus.PAID);
                leaseMapper.updateById(lease);
                changeLogService.logDepositRefund(lease.getId(), "押金退还成功",
                        boundedChangeDetail("金额 " + refund.getAmount().toPlainString() + " 元，渠道 " + refund.getChannel()
                                + "，渠道单号 " + result.channelRefundNo()));
                notifyResident(lease, "押金已退还",
                        "您的租约押金 " + refund.getAmount().toPlainString() + " 元已按原渠道（"
                                + refund.getChannel() + "）退回，请注意查收。");
                log.info("押金退还成功：leaseId={}, refundId={}, amount={}, channelRefundNo={}, depositStatus={}",
                        lease.getId(), refund.getId(), refund.getAmount(), result.channelRefundNo(),
                        lease.getDepositStatus());
            });
        } else {
            transactionTemplate.executeWithoutResult(s -> {
                String failReason = result != null ? result.failReason() : "渠道未返回结果";
                String stored = truncate(failReason);
                refund.setStatus(DepositRefundStatus.FAILED);
                refund.setFailReason(stored);
                refundMapper.updateById(refund);

                /* 失败回落「已缴」：换新请求号可重试（历史失败记录追加留痕不覆盖） */
                lease.setDepositStatus(DepositStatus.PAID);
                leaseMapper.updateById(lease);
                changeLogService.logDepositRefund(lease.getId(), "押金退还失败",
                        boundedChangeDetail("金额 " + refund.getAmount().toPlainString() + " 元，原因：" + stored
                                + (StringUtils.hasText(remark) ? "；备注：" + remark : "")));
                notifyResident(lease, "押金退还失败",
                        "您的租约押金 " + refund.getAmount().toPlainString() + " 元退还失败："
                                + stored + "，可联系社区管理员重新发起。");
                log.warn("押金退还失败：leaseId={}, refundId={}, reason={}", lease.getId(), refund.getId(), stored);
            });
        }
        return toVO(refund);
    }

    /* 实缴押金口径（DEF-103，2026-09-23 决策日志）：存在 SUCCESS 首期单即视为押金已实缴，
       实缴额以租约登记押金 lease_record.deposit 为对照基准——原「首期单金额 − 当前月租」
       推算法在月租事后被编辑时会得到错误押金部分（V25 已应用禁改，其回填判据同源受影响，
       两库当前无月租事后变更数据，口径兼容；V28 迁移不对账，仅决策日志留痕） */
    private BigDecimal paidDeposit(LeaseRecord lease, List<LeasePayment> paidList) {
        if (paidList.isEmpty()) return BigDecimal.ZERO;
        if (paidList.stream().allMatch(p->p.getDepositAmount()!=null))
            return paidList.stream().map(com.community.residence.payment.entity.LeasePayment::getDepositAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        /* 旧单未保存快照，仍按原兼容口径读取，不写回猜测值；新单只使用实缴快照。 */
        return lease.getDeposit()==null?BigDecimal.ZERO:lease.getDeposit();
    }

    /** 已成功退还金额合计（SUCCESS 单；失败/处理中不计入，故失败可重试不重复扣减） */
    private BigDecimal refundedAmount(Long leaseId) {
        return refundMapper.selectList(new LambdaQueryWrapper<LeaseDepositRefund>()
                        .eq(LeaseDepositRefund::getLeaseId, leaseId)
                        .eq(LeaseDepositRefund::getStatus, DepositRefundStatus.SUCCESS))
                .stream()
                .map(r -> r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<LeasePayment> paidFirstPayments(Long leaseId) {
        return paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>()
                .eq(LeasePayment::getLeaseId, leaseId)
                .eq(LeasePayment::getPaymentType, PaymentType.FIRST)
                .eq(LeasePayment::getStatus, PaymentStatus.SUCCESS)
                .orderByDesc(LeasePayment::getId));
    }

    /* 已通过的退租申请（前置条件之一；同时作为退款记录的弱关联来源） */
    private LeaseMoveOutApplication approvedMoveOut(Long leaseId) {
        return moveOutApplicationMapper.selectOne(new LambdaQueryWrapper<LeaseMoveOutApplication>()
                .eq(LeaseMoveOutApplication::getLeaseId, leaseId)
                .eq(LeaseMoveOutApplication::getStatus, MoveOutStatus.APPROVED)
                .orderByDesc(LeaseMoveOutApplication::getId)
                .last("LIMIT 1"));
    }

    /* 押金状态读取：列默认 UNPAID，历史行/空值一律按未缴处理 */
    private String currentDepositStatus(LeaseRecord lease) {
        return StringUtils.hasText(lease.getDepositStatus())
                ? lease.getDepositStatus() : DepositStatus.UNPAID;
    }

    /* 同号幂等命中的一致性判据：租约与金额（金额留空视为同额）一致返回既有，
       否则请求号被复用 → 5706；先查与撞库重查两条路径共用同一口径 */
    private DepositRefundVO existingOrConflict(LeaseDepositRefund existing, Long leaseId,
                                               CreateDepositRefundDTO dto) {
        boolean sameLease = existing.getLeaseId().equals(leaseId);
        boolean sameAmount = dto.getAmount() == null
                || existing.getAmount().compareTo(dto.getAmount()) == 0;
        if (!sameLease || !sameAmount) {
            throw new BusinessException(ErrorCode.DEPOSIT_OUT_REQUEST_CONFLICT);
        }
        log.info("押金退还重复提交命中幂等键，返回既有记录：outRequestNo={}, refundId={}, status={}",
                dto.getOutRequestNo(), existing.getId(), existing.getStatus());
        return toVO(existing);
    }

    private PaymentChannelClient clientOf(String channel) {
        if (PaymentChannel.WECHAT.equals(channel)) {
            return wechatChannelClient;
        }
        return alipayChannelClient;
    }

    private void notifyResident(LeaseRecord lease, String title, String content) {
        notificationService.createForResident(lease.getTenantId(), lease.getCommunityId(),
                title, content, "LEASE", "LEASE", lease.getId());
    }

    private LeaseRecord requireLease(Long leaseId) {
        LeaseRecord lease = leaseMapper.selectById(leaseId);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        return lease;
    }

    /* 锁释放兜底：锁键过期后 unlock 会抛 IllegalMonitorStateException，吞掉不影响已落账结果 */
    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (Exception e) {
            log.warn("押金退还锁释放异常（锁键已过期时正常）", e);
        }
    }

    /* 操作人姓名（发起人恒为管理端账号，取 sys_user.real_name；账号缺失回落 null，VO 中姓名为空） */
    private String operatorName(Long operatorId) {
        if (operatorId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(operatorId);
        if (user == null) {
            return null;
        }
        return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername();
    }

    /* 租约变更历史 reason 列宽 500；退款失败原因与备注拼接后仍须整体截断，避免事务二因超长回滚 */
    private String boundedChangeDetail(String text) {
        return truncate(text);
    }

    /* 渠道文案截断：fail_reason 列宽 500，超长截断避免插入异常 */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > FAIL_REASON_MAX_LENGTH ? text.substring(0, FAIL_REASON_MAX_LENGTH) : text;
    }

    private DepositRefundVO toVO(LeaseDepositRefund refund) {
        DepositRefundVO vo = DepositRefundVO.from(refund);
        vo.setOperatorName(operatorName(refund.getOperatorId()));
        return vo;
    }
}
