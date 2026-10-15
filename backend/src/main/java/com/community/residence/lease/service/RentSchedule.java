package com.community.residence.lease.service;

import com.community.residence.lease.entity.LeaseContractTerm;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;

/** 全合同日历月账期：原起租日锚定，不从截短月末滚动加月；历史覆盖缺失绝不猜测。 */
public final class RentSchedule {
 public record Month(Long termId, LocalDate start, LocalDate end, BigDecimal rent, boolean paid) { }
 public record Slice(Long termId, LocalDate start, LocalDate end, int months, BigDecimal monthlyRent, BigDecimal amount) { }
 public record Quote(int remaining, int months, LocalDate start, LocalDate end, BigDecimal amount, List<Slice> slices, String version) { }
 private RentSchedule() { }
 private static BusinessException invalid(String text) { return new BusinessException(ErrorCode.INVALID_PARAM,text); }
 public static List<Month> build(List<LeaseContractTerm> allTerms, List<LeasePayment> payments) {
  var terms=allTerms.stream().filter(t->"ACTIVE".equals(t.getStatus())).sorted(Comparator.comparing(LeaseContractTerm::getStartDate)).toList();
  if(terms.isEmpty()) throw invalid("没有生效的合同期限，请先确认首期支付结果");
  LocalDate anchor=terms.get(0).getStartDate();int offset=0;
  List<Month> months=new ArrayList<>();
  for(var term:terms) {
   if(term.getContractMonths()==null || term.getContractMonths()<1 || term.getMonthlyRent()==null
       || !term.getStartDate().equals(anchor.plusMonths(offset))
       || !term.getEndDate().equals(anchor.plusMonths(offset+term.getContractMonths()).minusDays(1)))
    throw invalid("历史合同期限与原始日历月锚点不一致，请管理方先核对");
   for(int i=0;i<term.getContractMonths();i++) months.add(new Month(term.getId(),anchor.plusMonths(offset+i),anchor.plusMonths(offset+i+1).minusDays(1),term.getMonthlyRent(),false));
   offset+=term.getContractMonths();
  }
  for(var payment:payments) {
   if(!"SUCCESS".equals(payment.getStatus()) || payment.getSettlementIssue()!=null)continue;
   if(payment.getPeriodStart()==null || payment.getPeriodEnd()==null || payment.getMonths()==null)throw invalid("历史支付缺少覆盖快照，请先核对，不按当前价格反推");
   int first=-1,last=-1;
   for(int i=0;i<months.size();i++) {if(months.get(i).start().equals(payment.getPeriodStart()))first=i;if(months.get(i).end().equals(payment.getPeriodEnd()))last=i;}
   if(first<0 || last<first || last-first+1!=payment.getMonths())throw invalid("历史租金覆盖不连续或超出合同，请先核对");
   for(int i=first;i<=last;i++) {var m=months.get(i);if(m.paid())throw invalid("历史租金存在重复覆盖，请先核对");months.set(i,new Month(m.termId(),m.start(),m.end(),m.rent(),true));}
  }
  boolean unpaid=false;
  for(var month:months) {if(!month.paid())unpaid=true;else if(unpaid)throw invalid("历史租金覆盖存在空档，请先核对，不重复收已缴月份");}
  return List.copyOf(months);
 }
 public static Quote quote(List<Month> schedule, int count) {
  var remaining=schedule.stream().filter(m->!m.paid()).toList();
  if(count<1 || count>remaining.size())throw invalid(remaining.isEmpty()?"合同内租金已缴清":"本次月数须在1至"+remaining.size()+"之间");
  var selected=remaining.subList(0,count);List<Slice> slices=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;
  for(var m:selected) {
   total=total.add(m.rent());
   if(!slices.isEmpty() && Objects.equals(slices.get(slices.size()-1).termId(),m.termId())) {
    var old=slices.remove(slices.size()-1);slices.add(new Slice(old.termId(),old.start(),m.end(),old.months()+1,old.monthlyRent(),old.amount().add(m.rent())));
   } else slices.add(new Slice(m.termId(),m.start(),m.end(),1,m.rent(),m.rent()));
  }
  String material=schedule.toString()+"|"+count;
  String version=org.springframework.util.DigestUtils.md5DigestAsHex(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  return new Quote(remaining.size(),count,selected.get(0).start(),selected.get(count-1).end(),total.setScale(2),List.copyOf(slices),version);
 }
}
