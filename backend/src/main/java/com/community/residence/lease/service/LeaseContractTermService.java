package com.community.residence.lease.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.lease.entity.*;
import com.community.residence.lease.mapper.*;
import com.community.residence.lease.dto.RenewLeaseDTO;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.constant.*;
import com.community.residence.common.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
@Service @RequiredArgsConstructor
public class LeaseContractTermService {
    private final LeaseContractTermMapper terms;
    private final LeaseRecordMapper leases;
    private final LeaseChangeLogService changeLog;
    private final com.community.residence.payment.mapper.LeasePaymentMapper payments;
    private void access(LeaseRecord lease){
        if(lease==null)throw new ResourceNotFoundException("租约不存在");
        if(SecurityUtils.hasRole(RoleConstants.RESIDENT)){
            if(!Objects.equals(lease.getTenantId(),SecurityUtils.getUserId()))throw new ForbiddenException("无权访问该租约");
        }
        else SecurityUtils.checkCommunityAccess(lease.getCommunityId());
    }
    public List<com.community.residence.lease.vo.LeaseContractTermVO> list(Long leaseId) {
        access(leases.selectById(leaseId));
        var successful = payments.selectList(new LambdaQueryWrapper<com.community.residence.payment.entity.LeasePayment>()
        .eq(com.community.residence.payment.entity.LeasePayment::getLeaseId,leaseId)
        .eq(com.community.residence.payment.entity.LeasePayment::getStatus,"SUCCESS")
        .orderByAsc(com.community.residence.payment.entity.LeasePayment::getPeriodStart));
        return rows(leaseId).stream().map(t -> {
            var vo = new com.community.residence.lease.vo.LeaseContractTermVO();
            org.springframework.beans.BeanUtils.copyProperties(t,vo);
            var paid=successful.stream().filter(p->Objects.equals(p.getTermId(),t.getId()) && p.getSettlementIssue()==null).toList();
            int count=paid.stream().mapToInt(p->p.getMonths()==null?0:p.getMonths()).sum();
            vo.setPaidMonths(count);vo.setRemainingMonths(Math.max(0,t.getContractMonths()-count));
            vo.setPaidPeriods(paid.stream().map(p->new com.community.residence.lease.vo.LeaseContractTermVO.Coverage(p.getPaymentNo(),p.getPeriodStart(),p.getPeriodEnd())).toList());
            if ("ACTIVE".equals(t.getStatus()) && count<t.getContractMonths()) {
                vo.setNextDueDate(t.getStartDate().plusMonths(count));
                vo.setOverdue(vo.getNextDueDate().isBefore(LocalDate.now()));
            }
            return vo;
        }
        ).toList();
    }
    public List<LeaseContractTerm> rows(Long leaseId){
        return terms.selectList(new LambdaQueryWrapper<LeaseContractTerm>().eq(LeaseContractTerm::getLeaseId,leaseId).orderByAsc(LeaseContractTerm::getId));
    }
    private String content(LeaseContractTerm t){
        return "租赁协议（平台内确认留痕，不构成可靠电子签名）\n租约："+t.getLeaseId()+"；类型："+t.getTermType()+"\n期限："+t.getStartDate()+" 至 "+t.getEndDate()+"（"+t.getContractMonths()+"个月）\n月租："+t.getMonthlyRent()+"元；首付："+t.getFirstPaymentMonths()+"个月；后续每"+t.getPaymentCycleMonths()+"个月支付；本段押金："+t.getDeposit()+"元。\n普通交租不延长合同；续租居民同意且该段首笔租金成功后生效，未生效方案取消不影响原合同。";
    }
    /* 在租约行锁内形成初始不可变费用约定，与发出的协议一并展示。 */
    public LeaseContractTerm initial(LeaseRecord lease){
        var found=rows(lease.getId()).stream().filter(t->"INITIAL".equals(t.getTermType())&&!"CANCELLED".equals(t.getStatus())).findFirst();
        if(found.isPresent())return found.get();
        if(lease.getContractMonths()==null)throw new BusinessException(ErrorCode.INVALID_PARAM,"历史租约缺少合同付款安排，请先由管理方核对，不能猜测历史费用");
        var t=new LeaseContractTerm();
        t.setLeaseId(lease.getId());
        t.setTermType("INITIAL");
        t.setStartDate(lease.getStartDate());
        t.setEndDate(lease.getEndDate());
        t.setContractMonths(lease.getContractMonths());
        t.setFirstPaymentMonths(LeaseTermRules.months(lease.getFirstPaymentMonths(),1));
        t.setPaymentCycleMonths(LeaseTermRules.months(lease.getPaymentCycleMonths(),1));
        t.setMonthlyRent(lease.getMonthlyRent());
        t.setDeposit(lease.getDeposit()==null?BigDecimal.ZERO:lease.getDeposit());
        t.setStatus("OFFERED");
        t.setContentSnapshot(content(t));
        terms.insert(t);
        return t;
    }
    public void signInitial(LeaseRecord lease){
        var t=initial(lease);
        if(!"ACTIVE".equals(t.getStatus())){
            t.setStatus("SIGNED");
            t.setConfirmedAt(LocalDateTime.now());
            terms.updateById(t);
        }
    }
    @Transactional(rollbackFor=Exception.class)
    public LeaseContractTerm propose(Long leaseId,RenewLeaseDTO dto){
        var lease=leases.selectByIdForUpdate(leaseId);
        access(lease);
        if(!"ACTIVE".equals(lease.getStatus())||lease.getEndDate().isBefore(LocalDate.now()))throw new BusinessException(ErrorCode.INVALID_PARAM,"仅未到期的有效租约可连续续租；断档请核对后新建租约");
        if(rows(leaseId).stream().anyMatch(t->"RENEWAL".equals(t.getTermType())&&Set.of("OFFERED","SIGNED").contains(t.getStatus())))throw new BusinessException(ErrorCode.INVALID_PARAM,"已有未生效续租协议，请先处理");
        if(dto.getMonths()==null&&dto.getNewEndDate()==null)throw new BusinessException(ErrorCode.INVALID_PARAM,"请设置新增合同月数");
        var start=lease.getEndDate().plusDays(1);
        int months=dto.getMonths()==null?(int)java.time.temporal.ChronoUnit.MONTHS.between(start,dto.getNewEndDate().plusDays(1)):dto.getMonths();
        int first=LeaseTermRules.months(dto.getFirstPaymentMonths(),1),cycle=LeaseTermRules.months(dto.getPaymentCycleMonths(),1);
        LeaseTermRules.validate(months,first,cycle);
        var end=LeaseTermRules.end(start,months);
        if(dto.getNewEndDate()!=null&&!end.equals(dto.getNewEndDate()))throw new BusinessException(ErrorCode.INVALID_PARAM,"续租止期须按新增起租日对齐日历月");
        if(dto.getDeposit()!=null&&lease.getDeposit()!=null&&dto.getDeposit().compareTo(lease.getDeposit())!=0)throw new BusinessException(ErrorCode.INVALID_PARAM,"续租沿用原押金，本入口不支持押金差额调整");
        var t=new LeaseContractTerm();
        t.setLeaseId(leaseId);
        t.setTermType("RENEWAL");
        t.setStartDate(start);
        t.setEndDate(end);
        t.setContractMonths(months);
        t.setFirstPaymentMonths(first);
        t.setPaymentCycleMonths(cycle);
        t.setMonthlyRent(dto.getMonthlyRent());
        t.setDeposit(BigDecimal.ZERO);
        t.setStatus("OFFERED");
        t.setContentSnapshot(content(t));
        terms.insert(t);
        changeLog.logAgreement(leaseId,"RENEWAL_OFFERED","拟定续租至 "+t.getEndDate()+"，新增段首付 "+first+" 个月，原合同尚未延长");
        return t;
    }
    @Transactional(rollbackFor=Exception.class)
    public LeaseContractTerm confirm(Long leaseId,Long termId){
        var lease=leases.selectByIdForUpdate(leaseId);
        access(lease);
        if(!Objects.equals(lease.getTenantId(),SecurityUtils.getUserId()))throw new ForbiddenException("仅租客本人可同意");
        var t=terms.lock(termId);
        if(t==null||!Objects.equals(t.getLeaseId(),leaseId)||!"RENEWAL".equals(t.getTermType()))throw new ResourceNotFoundException("续租协议不存在");
        if(!Set.of("OFFERED","SIGNED").contains(t.getStatus())||!"ACTIVE".equals(lease.getStatus()))throw new BusinessException(ErrorCode.INVALID_PARAM,"当前续租协议不可确认");
        if("OFFERED".equals(t.getStatus())){
            t.setStatus("SIGNED");
            t.setConfirmedAt(LocalDateTime.now());
            terms.updateById(t);
            changeLog.logAgreement(leaseId,"RENEWAL_CONFIRMED","居民同意新增段，待首笔租金成功");
        }
        return t;
    }
    @Transactional(rollbackFor=Exception.class)
    public void cancel(Long leaseId,Long termId){
        var lease=leases.selectByIdForUpdate(leaseId);
        access(lease);
        var t=terms.lock(termId);
        if(t==null||!Objects.equals(t.getLeaseId(),leaseId))throw new ResourceNotFoundException("协议不存在");
        if(!Set.of("OFFERED","SIGNED","CANCELLED").contains(t.getStatus())||!"RENEWAL".equals(t.getTermType()))throw new BusinessException(ErrorCode.INVALID_PARAM,"仅未生效续租可取消");
        if (!"CANCELLED".equals(t.getStatus())) changeLog.logAgreement(leaseId,"RENEWAL_CANCELLED","取消未生效新增段，不改变原约");
        t.setStatus("CANCELLED");
        terms.updateById(t);
    }
    public LeaseContractTerm requirePayable(Long leaseId, String purpose) {
        if (purpose == null || !Set.of("FIRST", "RENT", "RENEWAL").contains(purpose))
        throw new BusinessException(ErrorCode.INVALID_PARAM, "未知支付用途");
        return rows(leaseId).stream().filter(t -> switch (purpose) {
            case "FIRST" -> "INITIAL".equals(t.getTermType()) && "SIGNED".equals(t.getStatus());
            case "RENEWAL" -> "RENEWAL".equals(t.getTermType()) && "SIGNED".equals(t.getStatus());
            default -> "ACTIVE".equals(t.getStatus());
        }
        ).findFirst().orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAM, "没有符合付款条件的已确认合同段"));
    }
    public boolean canSettle(Long termId,Long leaseId,String purpose){
        var t=terms.lock(termId);
        return t!=null&&Objects.equals(t.getLeaseId(),leaseId)&&("RENT".equals(purpose) ? "ACTIVE".equals(t.getStatus())
        : "SIGNED".equals(t.getStatus()) && ("FIRST".equals(purpose) ? "INITIAL".equals(t.getTermType())
        : "RENEWAL".equals(purpose) && "RENEWAL".equals(t.getTermType())));
    }
    public void cancelInitial(Long leaseId){
        for(var t:rows(leaseId))if("INITIAL".equals(t.getTermType())&&Set.of("OFFERED","SIGNED").contains(t.getStatus())){
            t.setStatus("CANCELLED");
            terms.updateById(t);
        }
    }
    /* 已持租约/支付锁；只首笔续租延展，随后RENT仅增加覆盖。 */
    public void activate(Long termId,LeaseRecord lease){
        var t=terms.lock(termId);
        if(t==null||!Objects.equals(t.getLeaseId(),lease.getId())||!"SIGNED".equals(t.getStatus()))throw new BusinessException(ErrorCode.INVALID_PARAM,"合同段已变化，不能结算");
        if("RENEWAL".equals(t.getTermType())){
            if(!t.getStartDate().equals(lease.getEndDate().plusDays(1)))throw new BusinessException(ErrorCode.INVALID_PARAM,"续租期间不连续");
            var before=new LeaseRecord();
            org.springframework.beans.BeanUtils.copyProperties(lease,before);
            lease.setEndDate(t.getEndDate());
            leases.updateById(lease);
            changeLog.logRenew(before,lease,"续租协议已同意且新增段首笔租金成功");
        }
        t.setStatus("ACTIVE");
        terms.updateById(t);
    }
}
