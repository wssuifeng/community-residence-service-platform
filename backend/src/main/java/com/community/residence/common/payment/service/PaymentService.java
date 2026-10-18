package com.community.residence.common.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.lease.constant.DepositStatus;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.lease.constant.LeaseStatus;
import com.community.residence.common.payment.constant.PaymentChannel;
import com.community.residence.common.payment.constant.PaymentStatus;
import com.community.residence.common.payment.constant.PaymentType;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.payment.config.PaymentProperties;
import com.community.residence.common.payment.dto.CreateFirstPaymentDTO;
import com.community.residence.common.payment.dto.CreateRenewPaymentDTO;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.common.payment.service.channel.ChannelTradeResult;
import com.community.residence.common.payment.service.channel.PaymentChannelClient;
import com.community.residence.common.payment.vo.LeasePaymentVO;
import com.community.residence.common.payment.vo.PaymentChannelVO;
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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按不可变合同段及费用覆盖处理FIRST/RENT/RENEWAL。
 * 本地显式事务保证单据、合同段、正式关系及通知原子；渠道调用在锁/事务外。
 * 普通交租不延长合同；续租仅新增段首次成功延长；迟到成功保留异常不复活租约。
 */
@Slf4j
@Service
public class PaymentService {

    /** 业务单号前缀与日期段（RENEW/FIRST + yyyyMMdd + 6位随机，对齐工单号风格） */
    private static final String PAYMENT_NO_PREFIX = "RENEW";
    private static final String FIRST_PAYMENT_NO_PREFIX = "FIRST";
    private static final DateTimeFormatter PAYMENT_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAYMENT_NO_RETRY_LIMIT = 5;

    /** 首期支付渠道订单标题（与续期区分，便于对账） */
    private static final String FIRST_PAYMENT_SUBJECT = "租约首期费用（约定首期租金+押金）";

    private final LeasePaymentMapper paymentMapper;
    private final LeaseRecordMapper leaseMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final NotificationService notificationService;
    private final PaymentProperties paymentProperties;
    private final PaymentChannelClient alipayChannelClient;
    private final PaymentChannelClient wechatChannelClient;
    private RedissonClient redissonClient;
    private org.springframework.transaction.support.TransactionTemplate settlementTransaction;
    @org.springframework.beans.factory.annotation.Autowired
    private com.community.residence.common.lease.service.LeaseContractTermService termService;
    private com.community.residence.common.lease.service.LeaseService leaseService;

    /** 本地落账显式事务：不依赖同类调用的@Transactional代理；远程查单在事务外。 */
    @Autowired
    public void setSettlementInfrastructure(org.springframework.transaction.PlatformTransactionManager manager,
            com.community.residence.common.lease.service.LeaseService service) {
        this.settlementTransaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.settlementTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.leaseService = service;
    }


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
                PaymentChannelVO.of(PaymentChannel.ALIPAY, alipayChannelClient.enabled(), alipayChannelClient.configured()),
                PaymentChannelVO.of(PaymentChannel.WECHAT, wechatChannelClient.enabled(), wechatChannelClient.configured()));
    }

    /**
     * 发起续约支付（RESIDENT）：租约须本人且 ACTIVE；幂等（本人已有 PENDING 单直接返回，
     * 同租约同期仅一单进行中——决策日志口径，应用层校验）；金额=月租×月数服务端计算；
     * 付款月数由已确认新增段决定；凭据获取失败保留可恢复单，不提前关闭仍可支付的订单。
     */
    @com.community.residence.common.log.annotation.OperationLog(operationType = "CREATE",
            targetType = "LEASE_PAYMENT", targetId = "#result.id",
            content = "'发起租约续约支付（' + #dto.channel + '，' + #dto.months + ' 个月）'")
    public LeasePaymentVO createRenewPayment(Long leaseId, CreateRenewPaymentDTO dto) {
        return createContractPayment(leaseId,dto.getChannel(),PaymentType.RENEWAL);
    }

    public LeasePaymentVO createRentPayment(Long leaseId, CreateRenewPaymentDTO dto) {
        return createSelectedRentPayment(leaseId,dto);
    }

    /** 按居民已确认合同段发起首期；只收约定首付月数与一次押金，不延长合同。 */
    @com.community.residence.common.log.annotation.OperationLog(operationType = "CREATE",
            targetType = "LEASE_PAYMENT", targetId = "#result.id",
            content = "'发起首期费用支付（' + #dto.channel + '）'")
    public LeasePaymentVO createFirstPayment(Long leaseId, CreateFirstPaymentDTO dto) {
        return createContractPayment(leaseId,dto.getChannel(),PaymentType.FIRST);
    }

    public LeasePaymentVO quote(Long leaseId,String purpose) { return quote(leaseId,purpose,1); }

    public LeasePaymentVO quote(Long leaseId,String purpose,Integer requestedMonths) {
        LeaseRecord lease=leaseMapper.selectById(leaseId);
        if(lease==null)throw new ResourceNotFoundException("租约不存在");
        if(!Objects.equals(lease.getTenantId(),SecurityUtils.getUserId()))throw new ForbiddenException("无权读取他人租金报价");
        if(PaymentType.RENT.equals(purpose)) {
            if(!"ACTIVE".equals(lease.getStatus()))throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID);
            var rows=paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getLeaseId,leaseId).orderByAsc(LeasePayment::getId));
            var pending=rows.stream().filter(this::stillPending).findFirst();
            if(pending.isPresent())return toVO(pending.get(),null,lease);
            var q=rentQuote(leaseId,rows,requestedMonths==null?1:requestedMonths);
            LeasePayment draft=rentDraft(lease,q);var vo=toVO(draft,null,lease);vo.setRemainingMonths(q.remaining());vo.setQuoteVersion(q.version());return vo;
        }
        if(PaymentType.RENEWAL.equals(purpose)) requireNoDueRent(leaseId);
        var term=payableTerm(leaseId,purpose);int paid=0;
        for(var row:paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getTermId,term.getId()).eq(LeasePayment::getStatus,PaymentStatus.SUCCESS)))paid+=row.getMonths();
        int months=PaymentType.RENT.equals(purpose)?Math.min(term.getPaymentCycleMonths(),term.getContractMonths()-paid):term.getFirstPaymentMonths();
        LeasePayment draft=new LeasePayment();draft.setLeaseId(leaseId);draft.setPaymentType(purpose);draft.setMonths(months);draft.setRentAmount(com.community.residence.common.lease.service.LeaseTermRules.rent(term.getMonthlyRent(),months));draft.setDepositAmount(PaymentType.FIRST.equals(purpose)?term.getDeposit():BigDecimal.ZERO);draft.setAmount(draft.getRentAmount().add(draft.getDepositAmount()));draft.setPeriodStart(term.getStartDate().plusMonths(paid));draft.setPeriodEnd(term.getStartDate().plusMonths(paid+months).minusDays(1));
        LeasePaymentVO vo=LeasePaymentVO.from(draft);fillAmountBreakdown(vo,draft,lease);return vo;
    }

    private com.community.residence.common.lease.entity.LeaseContractTerm payableTerm(Long leaseId,String purpose) {
        if(!PaymentType.RENT.equals(purpose))return termService.requirePayable(leaseId,purpose);
        for(var term:termService.rows(leaseId)) {
            if(!"ACTIVE".equals(term.getStatus()))continue;
            int paid=paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getTermId,term.getId()).eq(LeasePayment::getStatus,PaymentStatus.SUCCESS)).stream().mapToInt(p->p.getMonths()==null?0:p.getMonths()).sum();
            if(paid<term.getContractMonths())return term;
        }
        throw new BusinessException(ErrorCode.INVALID_PARAM,"当前生效合同段均已缴清租金");
    }

    /* 先处理已经到期的原段租金，不能以续租首付掩盖欠租；未到期租金仍按原段独立展示。 */
    private void requireNoDueRent(Long leaseId) {
        for (var term : termService.rows(leaseId)) {
            if (!"ACTIVE".equals(term.getStatus())) continue;
            int paid = paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>()
                    .eq(LeasePayment::getTermId,term.getId()).eq(LeasePayment::getStatus,PaymentStatus.SUCCESS))
                    .stream().mapToInt(p -> p.getMonths() == null ? 0 : p.getMonths()).sum();
            if (paid < term.getContractMonths() && !term.getStartDate().plusMonths(paid).isAfter(LocalDate.now()))
                throw new BusinessException(ErrorCode.INVALID_PARAM,"请先缴清已到期租金，再支付续租首笔租金");
        }
    }

    /* 同一租约三种付款共用串行与事务；渠道调用在本地单据提交后进行。 */
    private LeasePaymentVO createContractPayment(Long leaseId,String channel,String purpose) {
        LeaseRecord reference=leaseMapper.selectById(leaseId);
        if(reference==null)throw new ResourceNotFoundException("租约不存在");
        if(!Objects.equals(reference.getTenantId(),SecurityUtils.getUserId()))throw new ForbiddenException("无权对他人租约付款");
        PaymentChannelClient client=clientOf(channel);
        if(!client.enabled())throw new BusinessException(ErrorCode.PAYMENT_CHANNEL_DISABLED);
        RLock lock=redissonClient.getLock("lock:payment:contract:"+leaseId);boolean locked=false;
        LeasePayment payment;
        try {
            try{locked=lock.tryLock(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}
            if(!locked)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            payment=settlementTransaction.execute(status->{
                LeaseRecord lease=leaseService.lockForSettlement(leaseId);
                if(PaymentType.FIRST.equals(purpose)) {
                    if(!"TO_SIGN".equals(lease.getStatus())||!"SIGNED".equals(lease.getAgreementStatus()))throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID,"请先确认协议");
                } else if(!"ACTIVE".equals(lease.getStatus()))throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID);
                if(PaymentType.RENEWAL.equals(purpose)) requireNoDueRent(leaseId);
                var term=payableTerm(leaseId,purpose);
                var existing=paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getTermId,term.getId()).orderByAsc(LeasePayment::getId));
                int paid=0;
                for(var row:existing) {
                    if (row.getSettlementIssue() != null) throw new BusinessException(ErrorCode.INVALID_PARAM, "存在渠道到账待核对记录，请先联系管理方处理，勿重复支付");
                    if(PaymentStatus.PENDING.equals(row.getStatus())) {
                        if(row.getExpiresAt()!=null&&!row.getExpiresAt().isAfter(LocalDateTime.now())){row.setStatus(PaymentStatus.CLOSED);paymentMapper.updateById(row);}else return row;
                    }
                    if(PaymentStatus.SUCCESS.equals(row.getStatus())){
                        if(PaymentType.FIRST.equals(purpose)||PaymentType.RENEWAL.equals(purpose))throw new BusinessException(ErrorCode.PAYMENT_ALREADY_EXISTS);
                        if(row.getPeriodStart()==null||!row.getPeriodStart().equals(term.getStartDate().plusMonths(paid)))throw new BusinessException(ErrorCode.INVALID_PARAM,"历史租金覆盖不连续，请先核对");paid+=row.getMonths();
                    }
                }
                /* 先续接仍有效的首期单；跨零点不能截短已经授予的15分钟付款窗口。 */
                if(PaymentType.FIRST.equals(purpose)&&lease.getStartDate().isBefore(LocalDate.now()))throw new BusinessException(ErrorCode.LEASE_EXPIRED,"已过计划起租日且首期未付，申请将自动驳回，请重新申请");
                if(PaymentType.RENEWAL.equals(purpose))throw new BusinessException(ErrorCode.INVALID_PARAM,"旧续租方案不再新建首笔付款，请取消未生效方案后申请延长租期；已有进行中订单仍沿原单继续");
                if(paid>=term.getContractMonths())throw new BusinessException(ErrorCode.INVALID_PARAM,"该合同段租金已全部缴清");
                int months=PaymentType.RENT.equals(purpose)?Math.min(term.getPaymentCycleMonths(),term.getContractMonths()-paid):term.getFirstPaymentMonths();
                LeasePayment created=new LeasePayment();created.setPaymentNo(generatePaymentNo(PaymentType.FIRST.equals(purpose)?FIRST_PAYMENT_NO_PREFIX:PAYMENT_NO_PREFIX));
                created.setLeaseId(leaseId);created.setResidentId(lease.getTenantId());created.setTermId(term.getId());created.setPaymentType(purpose);created.setChannel(channel);created.setMonths(months);
                created.setMonthlyRentSnapshot(term.getMonthlyRent());created.setRentAmount(com.community.residence.common.lease.service.LeaseTermRules.rent(term.getMonthlyRent(),months));created.setDepositAmount(PaymentType.FIRST.equals(purpose)?term.getDeposit():BigDecimal.ZERO);created.setAmount(created.getRentAmount().add(created.getDepositAmount()));
                created.setPeriodStart(term.getStartDate().plusMonths(paid));created.setPeriodEnd(term.getStartDate().plusMonths(paid+months).minusDays(1));
                var deadline=com.community.residence.common.lease.service.LeaseTermRules.channelDeadline(LocalDateTime.now());
                created.setChannelExpiresAt(deadline);created.setExpiresAt(com.community.residence.common.lease.service.LeaseTermRules.localDeadline(deadline));created.setStatus(PaymentStatus.PENDING);paymentMapper.insert(created);return created;
            });
        } finally {if(locked)unlockQuietly(lock);}
        if(payment==null)throw new BusinessException(ErrorCode.PAYMENT_CREATE_FAILED);
        return toVO(payment,credentialOf(payment));
    }

    private boolean stillPending(LeasePayment row) {
        return PaymentStatus.PENDING.equals(row.getStatus()) && (row.getExpiresAt()==null || row.getExpiresAt().isAfter(LocalDateTime.now()));
    }
    private com.community.residence.common.lease.service.RentSchedule.Quote rentQuote(Long leaseId,List<LeasePayment> rows,int months) {
        if(rows.stream().anyMatch(p->p.getSettlementIssue()!=null))throw new BusinessException(ErrorCode.INVALID_PARAM,"存在到账待核对记录，请先联系管理方，勿重复支付");
        return com.community.residence.common.lease.service.RentSchedule.quote(com.community.residence.common.lease.service.RentSchedule.build(termService.rows(leaseId),rows),months);
    }
    private LeasePayment rentDraft(LeaseRecord lease,com.community.residence.common.lease.service.RentSchedule.Quote q) {
        LeasePayment draft=new LeasePayment();draft.setLeaseId(lease.getId());draft.setResidentId(lease.getTenantId());draft.setPaymentType(PaymentType.RENT);
        draft.setTermId(q.slices().get(0).termId());draft.setMonths(q.months());draft.setRentAmount(q.amount());draft.setDepositAmount(BigDecimal.ZERO);draft.setAmount(q.amount());
        draft.setMonthlyRentSnapshot(q.slices().size()==1?q.slices().get(0).monthlyRent():null);draft.setPeriodStart(q.start());draft.setPeriodEnd(q.end());
        var snapshots=q.slices().stream().map(part->java.util.Map.of("termId",part.termId(),"start",part.start().toString(),"end",part.end().toString(),"months",part.months(),"monthlyRent",part.monthlyRent(),"amount",part.amount())).toList();
        draft.setCoverageDetails(new tools.jackson.databind.ObjectMapper().writeValueAsString(snapshots));return draft;
    }
    /* 同租约锁与结算事务内重报价格；已有可支付单原样返回，不本地强关、不重复收取。 */
    private LeasePaymentVO createSelectedRentPayment(Long leaseId,CreateRenewPaymentDTO dto) {
        LeaseRecord reference=leaseMapper.selectById(leaseId);
        if(reference==null)throw new ResourceNotFoundException("租约不存在");
        if(!Objects.equals(reference.getTenantId(),SecurityUtils.getUserId()))throw new ForbiddenException("无权对他人租约付款");
        if(!clientOf(dto.getChannel()).enabled())throw new BusinessException(ErrorCode.PAYMENT_CHANNEL_DISABLED);
        RLock lock=redissonClient.getLock("lock:payment:contract:"+leaseId);boolean locked=false;LeasePayment payment;
        try {
            try{locked=lock.tryLock(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}
            if(!locked)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            payment=settlementTransaction.execute(status->{
                var lease=leaseService.lockForSettlement(leaseId);
                if(!"ACTIVE".equals(lease.getStatus()))throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID);
                var rows=paymentMapper.selectList(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getLeaseId,leaseId).orderByAsc(LeasePayment::getId));
                if(rows.stream().anyMatch(p->p.getSettlementIssue()!=null))throw new BusinessException(ErrorCode.INVALID_PARAM,"存在到账待核对记录，请联系管理方");
                for(var row:rows)if(PaymentStatus.PENDING.equals(row.getStatus())) {
                    if(stillPending(row))return row;
                    row.setStatus(PaymentStatus.CLOSED);paymentMapper.updateById(row);
                }
                var q=rentQuote(leaseId,rows,dto.getMonths()==null?1:dto.getMonths());
                if(!Objects.equals(q.version(),dto.getQuoteVersion()))throw new BusinessException(ErrorCode.INVALID_PARAM,"缴租报价已变化或未确认，请重新读取并核对金额/覆盖区间后支付");
                var created=rentDraft(lease,q);created.setPaymentNo(generatePaymentNo(PAYMENT_NO_PREFIX));created.setChannel(dto.getChannel());created.setStatus(PaymentStatus.PENDING);
                var deadline=com.community.residence.common.lease.service.LeaseTermRules.channelDeadline(LocalDateTime.now());
                created.setChannelExpiresAt(deadline);created.setExpiresAt(com.community.residence.common.lease.service.LeaseTermRules.localDeadline(deadline));paymentMapper.insert(created);return created;
            });
        }finally{if(locked)unlockQuietly(lock);}
        if(payment==null)throw new BusinessException(ErrorCode.PAYMENT_CREATE_FAILED);
        return toVO(payment,credentialOf(payment));
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

    /** 支付成功落账：首期激活/续期延展、支付单和通知同事务（同一事务，已 SUCCESS 不重复延展） */
    protected void settleSuccess(LeasePayment payment, ChannelTradeResult result) {
        if (result == null) return;
        settlementTransaction.executeWithoutResult(status -> applySettlement(payment, result));
    }

    /* 显式事务内：房屋→租约→支付单锁，同一笔成功仅处理一次。 */
    private void applySettlement(LeasePayment payment, ChannelTradeResult result) {
        /* 幂等重入保护：并发轮询下前一笔事务已落账则跳过 */
        LeasePayment current = paymentMapper.selectById(payment.getId());
        if (current == null || PaymentStatus.SUCCESS.equals(current.getStatus())) return;
        LeaseRecord lease = leaseService.lockForSettlement(current.getLeaseId());
        current = paymentMapper.selectByIdForUpdate(payment.getId());
        if (current == null || PaymentStatus.SUCCESS.equals(current.getStatus())) return;
        if (!PaymentStatus.PENDING.equals(current.getStatus()) || current.getTermId()==null
                || !Set.of("TO_SIGN","ACTIVE").contains(lease.getStatus())
                || !termService.canSettle(current.getTermId(),current.getLeaseId(),current.getPaymentType())
                || (current.getChannelExpiresAt()!=null && result.paidAt()!=null && result.paidAt().isAfter(current.getChannelExpiresAt()))) {
            current.setChannelTradeNo(result.channelTradeNo());current.setChannelSuccessAt(result.paidAt()!=null?result.paidAt():LocalDateTime.now());
            current.setSettlementIssue("LATE_OR_UNMATCHED_SUCCESS_REQUIRES_REVIEW");paymentMapper.updateById(current);
            payment.setSettlementIssue(current.getSettlementIssue());return;
        }
        if (PaymentType.FIRST.equals(current.getPaymentType())) {
            Long otherFirst = paymentMapper.selectCount(new LambdaQueryWrapper<LeasePayment>()
                .eq(LeasePayment::getLeaseId, current.getLeaseId()).eq(LeasePayment::getPaymentType, PaymentType.FIRST)
                .eq(LeasePayment::getStatus, PaymentStatus.SUCCESS).ne(LeasePayment::getId, current.getId()));
            if (otherFirst != null && otherFirst > 0) throw new BusinessException(ErrorCode.PAYMENT_ALREADY_EXISTS);
        }
        current.setStatus(PaymentStatus.SUCCESS);
        current.setChannelTradeNo(result.channelTradeNo());
        current.setPaidAt(result.paidAt() != null ? result.paidAt() : LocalDateTime.now());
        paymentMapper.updateById(current);
        if (PaymentType.FIRST.equals(current.getPaymentType())) {
            termService.activate(current.getTermId(),lease);
            if (!LeaseStatus.ACTIVE.equals(lease.getStatus())) {
                leaseService.applyTransition(lease, LeaseStatus.ACTIVE, "协议完成且首期费用支付成功，正式生效");
            }
            /* 首期成功激活租约与正式关系，但不顺延 end_date；租期沿用审核约定。 */
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
                    "您的首期费用（约定首期租金 + 押金，共 " + current.getAmount() + " 元）已确认到账。",
                    "LEASE", "LEASE_PAYMENT", current.getId());
            for (Long adminId : boundAdminIds(lease.getCommunityId())) {
                notificationService.createForUser(adminId, lease.getCommunityId(),
                        "社区租约首期费用到账",
                        "租住记录 #" + lease.getId() + " 的首期费用已到账（"
                                + current.getAmount() + " 元）。",
                        "LEASE", "LEASE_PAYMENT", current.getId());
            }
        } else if (PaymentType.RENEWAL.equals(current.getPaymentType())) {
            termService.activate(current.getTermId(),lease);
            notificationService.createForResident(current.getResidentId(),lease.getCommunityId(),"续租已生效","新增合同期生效至 "+lease.getEndDate()+"，后续交租不再延长期限。","LEASE","LEASE_PAYMENT",current.getId());
        } else if (PaymentType.RENT.equals(current.getPaymentType())) {
            notificationService.createForResident(current.getResidentId(),lease.getCommunityId(),"租金支付成功","本次租金覆盖 "+current.getPeriodStart()+" 至 "+current.getPeriodEnd()+"，合同止期不变。","LEASE","LEASE_PAYMENT",current.getId());
        } else throw new BusinessException(ErrorCode.INVALID_PARAM,"未知支付用途，不可结算");

        if(!PaymentType.FIRST.equals(current.getPaymentType()))for(Long adminId:boundAdminIds(lease.getCommunityId()))
            notificationService.createForUser(adminId,lease.getCommunityId(),"租约租金到账","租约 #"+lease.getId()+" 的 "+current.getPaymentType()+" 已到账；金额与覆盖期见支付单。","LEASE","LEASE_PAYMENT",current.getId());
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setChannelTradeNo(result.channelTradeNo());
        payment.setPaidAt(current.getPaidAt());
    }

    /** 关闭支付单（本人放弃支付）：仅 PENDING 可关（SUCCESS 已落账不可关，CLOSED 幂等） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS",
            targetType = "LEASE_PAYMENT", targetId = "#result", content = "'关闭续约支付单'")
    public Long closePayment(String paymentNo) {
        LeasePayment payment = requirePayment(paymentNo);
        payment = paymentMapper.selectByIdForUpdate(payment.getId());
        if (payment == null) throw new ResourceNotFoundException("支付单已不存在");
        if (!payment.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权操作他人支付单");
        }
        if (PaymentStatus.SUCCESS.equals(payment.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已支付的单不可关闭");
        }
        if (PaymentStatus.CLOSED.equals(payment.getStatus())) {
            return payment.getId();
        }
        if (payment.getExpiresAt()!=null && LocalDateTime.now().isBefore(payment.getExpiresAt()))
            throw new BusinessException(ErrorCode.INVALID_PARAM,"渠道订单仍可能支付，暂不能本地关单；关闭页面会保留订单，请等待该订单显示的系统到期时间");
        payment.setStatus(PaymentStatus.CLOSED);
        paymentMapper.updateById(payment);
        log.info("支付单已关闭（居民放弃支付）：paymentNo={}, operator={}",
                paymentNo, SecurityUtils.getUserId());
        return payment.getId();
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
        if (payment.getSettlementIssue() != null || !PaymentStatus.PENDING.equals(payment.getStatus()) || (payment.getChannelExpiresAt()!=null && !payment.getChannelExpiresAt().isAfter(LocalDateTime.now()))) {
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
        return (PaymentType.RENT.equals(payment.getPaymentType()) ? "租约租金-" : "续租首笔租金-") + payment.getMonths() + "个月";
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

    /** 金额和覆盖期只读支付单快照；旧单缺少快照时返回未知，不按当前租金反推。 */
    private void fillAmountBreakdown(LeasePaymentVO vo, LeasePayment payment, LeaseRecord lease) {
        vo.setRentAmount(payment.getRentAmount());vo.setDepositAmount(payment.getDepositAmount());
        vo.setPeriodStart(payment.getPeriodStart());vo.setPeriodEnd(payment.getPeriodEnd());
    }

    /* 首期实缴押金只读快照，与多月租金分开；不反推或重复收取。 */
    private BigDecimal depositPartOf(LeasePayment payment, LeaseRecord lease) {
        if (!PaymentType.FIRST.equals(payment.getPaymentType())) {
            return BigDecimal.ZERO;
        }
        return payment.getDepositAmount()==null?BigDecimal.ZERO:payment.getDepositAmount();
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
