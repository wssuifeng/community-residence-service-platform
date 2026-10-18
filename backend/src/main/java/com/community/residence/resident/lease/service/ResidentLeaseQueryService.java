package com.community.residence.resident.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.lease.constant.LeaseStatus;
import com.community.residence.common.lease.entity.LeaseContractTerm;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseContractTermMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.lease.service.RentSchedule;
import com.community.residence.common.lease.vo.LeaseVO;
import com.community.residence.common.payment.constant.PaymentStatus;
import com.community.residence.common.payment.constant.PaymentType;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.resident.lease.vo.ResidentLeaseSummaryVO;
import com.community.residence.resident.lease.vo.ResidentLeaseSummaryVO.Action;
import com.community.residence.resident.lease.vo.ResidentLeaseSummaryVO.Summary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 本人租约只读查询，批量读取本页快照，不调用任何渠道。 */
@Service
@RequiredArgsConstructor
public class ResidentLeaseQueryService {
    private final LeaseRecordMapper leases;
    private final LeaseContractTermMapper terms;
    private final LeasePaymentMapper payments;
    private final LeaseService leaseViews;

    /* 身份固定为居民本人；即使不同身份使用同一个数字ID，也不得进入读取。 */
    @Transactional(readOnly = true)
    public PageVO<ResidentLeaseSummaryVO> page(long page, long size) {
        if (!SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            throw new ForbiddenException("仅居民可读取本人租约摘要");
        }
        var result = leases.selectPage(
                new Page<LeaseRecord>(Math.max(1, page), Math.max(1, Math.min(size, 100))),
                new LambdaQueryWrapper<LeaseRecord>()
                        .eq(LeaseRecord::getTenantId, SecurityUtils.getUserId())
                        .orderByDesc(LeaseRecord::getId));
        var rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageVO.of(List.of(), result.getTotal(), result.getCurrent(), result.getSize());
        }
        var ids = rows.stream().map(LeaseRecord::getId).toList();
        var termGroups = terms.selectList(new LambdaQueryWrapper<LeaseContractTerm>()
                        .in(LeaseContractTerm::getLeaseId, ids))
                .stream().collect(Collectors.groupingBy(LeaseContractTerm::getLeaseId));
        var paymentGroups = payments.selectList(new LambdaQueryWrapper<LeasePayment>()
                        .in(LeasePayment::getLeaseId, ids).eq(LeasePayment::getStatus, PaymentStatus.SUCCESS))
                .stream().collect(Collectors.groupingBy(LeasePayment::getLeaseId));
        LocalDate today = LocalDate.now();
        var records = leaseViews.batchViews(rows).stream().map(lease -> {
            var card = new ResidentLeaseSummaryVO();
            card.setLease(lease);
            card.setSummary(summarize(lease, termGroups.getOrDefault(lease.getId(), List.of()),
                    paymentGroups.getOrDefault(lease.getId(), List.of()), today));
            return card;
        }).toList();
        return PageVO.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /* 复用真实账期算法；未知历史覆盖不能凭当前价格或支付月数猜测。 */
    static Summary summarize(LeaseVO lease, List<LeaseContractTerm> terms,
                             List<LeasePayment> payments, LocalDate today) {
        var first = payments.stream()
                .filter(payment -> PaymentStatus.SUCCESS.equals(payment.getStatus())
                        && payment.getSettlementIssue() == null
                        && PaymentType.FIRST.equals(payment.getPaymentType()))
                .min(Comparator.comparing(LeasePayment::getId, Comparator.nullsLast(Long::compareTo)))
                .orElse(null);
        Integer paid = null;
        Integer unpaid = null;
        String issue = "";
        boolean active = LeaseStatus.ACTIVE.equals(lease.getStatus());
        if (terms.stream().anyMatch(term -> "ACTIVE".equals(term.getStatus()))) {
            try {
                var schedule = RentSchedule.build(terms, payments);
                paid = (int) schedule.stream().filter(RentSchedule.Month::paid).count();
                unpaid = schedule.size() - paid;
            } catch (BusinessException error) {
                issue = error.getMessage();
            }
        } else if (active) {
            issue = "历史合同期限缺少有效快照，请管理方核对";
        }
        boolean signed = LeaseStatus.TO_SIGN.equals(lease.getStatus())
                && "SIGNED".equals(lease.getAgreementStatus());
        boolean initialSigned = terms.stream().anyMatch(term ->
                "INITIAL".equals(term.getTermType()) && "SIGNED".equals(term.getStatus()));
        boolean firstAllowed = (active || signed) && first == null && initialSigned
                && lease.getStartDate() != null && !lease.getStartDate().isBefore(today);

        var actions = new LinkedHashMap<String, Action>();
        actions.put("firstPayment", new Action(firstAllowed,
                firstPaymentReason(lease, first, initialSigned, today)));
        actions.put("rentPayment", new Action(active && unpaid != null && unpaid > 0,
                rentPaymentReason(active, unpaid, issue)));
        actions.put("extension", new Action(active && lease.getEndDate() != null
                && !lease.getEndDate().isBefore(today),
                "租期延长需提交申请并由管理方批准，普通缴租不延长合同"));
        actions.put("moveOut", new Action(active,
                active ? "提交退租申请，审批后结束居住关系" : "仅有效租约可申请退租"));
        return new Summary(displayStatus(lease, signed, today), first != null,
                first == null ? null : first.getAmount(), first == null ? null : first.getPaidAt(),
                paid, unpaid, Map.copyOf(actions));
    }

    private static String firstPaymentReason(LeaseVO lease, LeasePayment first,
                                             boolean initialSigned, LocalDate today) {
        if (first != null) return "首期已结清；合同内缴租不延长租期。";
        if (lease.getStartDate() != null && lease.getStartDate().isBefore(today)) {
            return "计划起租日已过，首期费用不可再支付，请等待自动驳回或联系管理方核对。";
        }
        if (!initialSigned) return "请先确认管理方发出的具体协议。";
        return "按约定首付月数收取租金及一次押金，成功后正式生效。";
    }

    private static String rentPaymentReason(boolean active, Integer unpaid, String issue) {
        if (!active) return "租约尚未生效或已结束";
        if (!issue.isEmpty()) return issue;
        if (unpaid != null && unpaid == 0) return "合同内租金已缴清";
        return "按合同内尚未缴纳月份支付，不延长合同";
    }

    private static String displayStatus(LeaseVO lease, boolean signed, LocalDate today) {
        return switch (lease.getStatus()) {
            case LeaseStatus.PENDING -> "待审核";
            case LeaseStatus.TO_SIGN -> signed ? "待支付首期费用" : "待签约";
            case LeaseStatus.ACTIVE -> lease.getStartDate() != null && lease.getStartDate().isAfter(today)
                    ? "已生效·待入住" : "在租";
            case LeaseStatus.MOVED_OUT -> "已搬出";
            case LeaseStatus.ARCHIVED -> "已归档";
            case LeaseStatus.REJECTED -> "已驳回";
            default -> lease.getStatus();
        };
    }
}
