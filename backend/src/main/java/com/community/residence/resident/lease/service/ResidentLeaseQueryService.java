package com.community.residence.resident.lease.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.lease.entity.*;
import com.community.residence.common.lease.mapper.*;
import com.community.residence.common.lease.service.*;
import com.community.residence.common.lease.vo.LeaseVO;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.resident.lease.vo.ResidentLeaseSummaryVO;
import com.community.residence.resident.lease.vo.ResidentLeaseSummaryVO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
/** 本人租约只读查询，批量读取本页快照，不调用任何渠道。 */
@Service @RequiredArgsConstructor
public class ResidentLeaseQueryService {
    private final LeaseRecordMapper leases;
    private final LeaseContractTermMapper terms;
    private final LeasePaymentMapper payments;
    private final LeaseService leaseViews;
    /* 身份固定为居民本人；即使不同身份使用同一个数字ID，也不得进入读取。 */
    @Transactional(readOnly=true)
    public PageVO<ResidentLeaseSummaryVO> page(long page,long size) {
        if (!SecurityUtils.hasRole("RESIDENT")) throw new ForbiddenException("仅居民可读取本人租约摘要");
        var result = leases.selectPage(new Page<LeaseRecord>(Math.max(1,page),Math.max(1,Math.min(size,100))),
            new LambdaQueryWrapper<LeaseRecord>().eq(LeaseRecord::getTenantId,SecurityUtils.getUserId()).orderByDesc(LeaseRecord::getId));
        var rows=result.getRecords();
        if(rows.isEmpty()) return PageVO.of(List.of(),result.getTotal(),result.getCurrent(),result.getSize());
        var ids=rows.stream().map(LeaseRecord::getId).toList();
        var termGroups=terms.selectList(new LambdaQueryWrapper<LeaseContractTerm>().in(LeaseContractTerm::getLeaseId,ids)).stream().collect(Collectors.groupingBy(LeaseContractTerm::getLeaseId));
        var paymentGroups=payments.selectList(new LambdaQueryWrapper<LeasePayment>().in(LeasePayment::getLeaseId,ids).eq(LeasePayment::getStatus,"SUCCESS")).stream().collect(Collectors.groupingBy(LeasePayment::getLeaseId));
        var records=leaseViews.batchViews(rows).stream().map(lease -> {
            var card=new ResidentLeaseSummaryVO();card.setLease(lease);
            card.setSummary(summarize(lease,termGroups.getOrDefault(lease.getId(),List.of()),paymentGroups.getOrDefault(lease.getId(),List.of()),LocalDate.now()));
            return card;
        }).toList();
        return PageVO.of(records,result.getTotal(),result.getCurrent(),result.getSize());
    }
    /* 复用真实账期算法；未知历史覆盖不能凭当前价格或支付月数猜测。 */
    static Summary summarize(LeaseVO lease,List<LeaseContractTerm> terms,List<LeasePayment> payments,LocalDate today) {
        var first=payments.stream().filter(p->"SUCCESS".equals(p.getStatus())&&p.getSettlementIssue()==null&&"FIRST".equals(p.getPaymentType())).min(Comparator.comparing(LeasePayment::getId,Comparator.nullsLast(Long::compareTo))).orElse(null);
        Integer paid=null,unpaid=null;String issue="";
        if(terms.stream().anyMatch(t->"ACTIVE".equals(t.getStatus()))) {
            try {var schedule=RentSchedule.build(terms,payments);paid=(int)schedule.stream().filter(RentSchedule.Month::paid).count();unpaid=schedule.size()-paid;}
            catch(BusinessException error){issue=error.getMessage();}
        } else if("ACTIVE".equals(lease.getStatus())) issue="历史合同期限缺少有效快照，请管理方核对";
        boolean active="ACTIVE".equals(lease.getStatus());
        boolean signed="TO_SIGN".equals(lease.getStatus())&&"SIGNED".equals(lease.getAgreementStatus());
        boolean initialSigned=terms.stream().anyMatch(t->"INITIAL".equals(t.getTermType())&&"SIGNED".equals(t.getStatus()));
        boolean firstAllowed=(active||signed)&&first==null&&initialSigned&&lease.getStartDate()!=null&&!lease.getStartDate().isBefore(today);
        String firstReason=first!=null?"首期已结清；合同内缴租不延长租期。":(lease.getStartDate()!=null&&lease.getStartDate().isBefore(today)?"计划起租日已过，首期费用不可再支付，请等待自动驳回或联系管理方核对。":!initialSigned?"请先确认管理方发出的具体协议。":"按约定首付月数收取租金及一次押金，成功后正式生效。");
        String display=switch(lease.getStatus()) {case "PENDING"->"待审核";case "TO_SIGN"->signed?"待支付首期费用":"待签约";case "ACTIVE"->lease.getStartDate()!=null&&lease.getStartDate().isAfter(today)?"已生效·待入住":"在租";case "MOVED_OUT"->"已搬出";case "ARCHIVED"->"已归档";case "REJECTED"->"已驳回";default->lease.getStatus();};
        var actions=new LinkedHashMap<String,Action>();
        actions.put("firstPayment",new Action(firstAllowed,firstReason));
        actions.put("rentPayment",new Action(active&&unpaid!=null&&unpaid>0, !active?"租约尚未生效或已结束":!issue.isEmpty()?issue:unpaid!=null&&unpaid==0?"合同内租金已缴清":"按合同内尚未缴纳月份支付，不延长合同"));
        actions.put("extension",new Action(active&&lease.getEndDate()!=null&&!lease.getEndDate().isBefore(today),"租期延长需提交申请并由管理方批准，普通缴租不延长合同"));
        actions.put("moveOut",new Action(active,active?"提交退租申请，审批后结束居住关系":"仅有效租约可申请退租"));
        return new Summary(display,first!=null,first==null?null:first.getAmount(),first==null?null:first.getPaidAt(),paid,unpaid,Map.copyOf(actions));
    }
}
