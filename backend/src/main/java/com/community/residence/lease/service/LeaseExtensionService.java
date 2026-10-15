package com.community.residence.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.lease.entity.*;
import com.community.residence.lease.mapper.*;
import com.community.residence.lease.dto.*;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.constant.*;
import com.community.residence.common.exception.*;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;

@Service @RequiredArgsConstructor
public class LeaseExtensionService {
 private final LeaseRecordMapper leases;
 private final LeaseExtensionApplicationMapper applications;
 private final LeaseContractTermMapper terms;
 private final LeaseContractTermService termService;
 private final LeaseChangeLogService history;
 private final NotificationService notifications;
 private final ConversationService conversations;
 private final SysAdminCommunityMapper bindings;
 private LeaseRecord access(Long id, boolean lock) {
  var lease=lock?leases.selectByIdForUpdate(id):leases.selectById(id);
  if(lease==null)throw new ResourceNotFoundException("租约不存在");
  if(SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
   if(!Objects.equals(lease.getTenantId(),SecurityUtils.getUserId()))throw new ForbiddenException("无权操作他人租约");
  } else SecurityUtils.checkCommunityAccess(lease.getCommunityId());
  return lease;
 }
 private void resident(LeaseRecord l) { if(!SecurityUtils.hasRole(RoleConstants.RESIDENT) || !Objects.equals(l.getTenantId(),SecurityUtils.getUserId()))throw new ForbiddenException("仅租客本人可申请或撤回"); }
 private void active(LeaseRecord l) { if(!"ACTIVE".equals(l.getStatus()) || l.getEndDate().isBefore(LocalDate.now()))throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID,"仅未到期的有效租约可申请延长，断档请重新申请入住"); }
 public List<LeaseExtensionApplication> pending() {
  if(!SecurityUtils.hasRole(RoleConstants.ADMIN)&&!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN))throw new ForbiddenException("仅管理员可读取待审申请");
  var q=new LambdaQueryWrapper<LeaseExtensionApplication>().eq(LeaseExtensionApplication::getStatus,"PENDING");
  if(!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {var ids=SecurityUtils.getCommunityIds();if(ids.isEmpty())return List.of();q.in(LeaseExtensionApplication::getCommunityId,ids);}
  return applications.selectList(q.orderByAsc(LeaseExtensionApplication::getId));
 }
 public List<LeaseExtensionApplication> list(Long leaseId) {access(leaseId,false);return applications.selectList(new LambdaQueryWrapper<LeaseExtensionApplication>().eq(LeaseExtensionApplication::getLeaseId,leaseId).orderByDesc(LeaseExtensionApplication::getId));}
 /* 租约行锁内只容许一份待审，申请即为居民同意快照；旧未生效RENEWAL须先明确取消。 */
 @Transactional(rollbackFor=Exception.class)
 public LeaseExtensionApplication apply(Long leaseId,LeaseExtensionDTO dto) {
  var lease=access(leaseId,true);resident(lease);active(lease);
  if(dto.getMonths()==null || dto.getMonths()<1 || dto.getMonths()>1200)throw new BusinessException(ErrorCode.INVALID_PARAM,"延长月数须为1至1200");
  if(applications.selectCount(new LambdaQueryWrapper<LeaseExtensionApplication>().eq(LeaseExtensionApplication::getLeaseId,leaseId).eq(LeaseExtensionApplication::getStatus,"PENDING"))>0)throw new BusinessException(ErrorCode.DATA_EXISTS,"已有待审租期延长申请");
  var rows=termService.rows(leaseId);
  if(rows.stream().anyMatch(t->"RENEWAL".equals(t.getTermType())&&Set.of("OFFERED","SIGNED").contains(t.getStatus())))throw new BusinessException(ErrorCode.INVALID_PARAM,"请先明确取消旧未生效续租方案；有效待支付单到期前不能强行取消");
  var effective=rows.stream().filter(t->"ACTIVE".equals(t.getStatus())).sorted(Comparator.comparing(LeaseContractTerm::getStartDate)).toList();
  RentSchedule.build(effective,List.of());
  int total=effective.stream().mapToInt(LeaseContractTerm::getContractMonths).sum();
  var expected=lease.getStartDate().plusMonths(total).minusDays(1);
  if(!expected.equals(lease.getEndDate()))throw new BusinessException(ErrorCode.INVALID_PARAM,"合同有效期限与段快照不一致，请先核对");
  var a=new LeaseExtensionApplication();a.setLeaseId(leaseId);a.setCommunityId(lease.getCommunityId());a.setResidentId(lease.getTenantId());
  a.setMonths(dto.getMonths());a.setOriginalEndDate(lease.getEndDate());a.setProposedEndDate(lease.getStartDate().plusMonths(total+dto.getMonths()).minusDays(1));
  a.setMonthlyRent(lease.getMonthlyRent());a.setDeposit(lease.getDeposit());a.setReason(dto.getReason());a.setStatus("PENDING");applications.insert(a);
  history.logAgreement(leaseId,"EXTENSION_APPLIED","居民申请延长"+a.getMonths()+"个月至"+a.getProposedEndDate()+"，审批前原期限不变");
  for(var binding:bindings.selectList(new LambdaQueryWrapper<SysAdminCommunity>().eq(SysAdminCommunity::getCommunityId,lease.getCommunityId())))
   notifications.createForUser(binding.getAdminId(),lease.getCommunityId(),"待审核租期延长申请","租约"+leaseId+"申请延长"+a.getMonths()+"个月，请在合同与租期核对审核。","LEASE","LEASE",leaseId);
  message(lease,"已提交租期延长申请："+a.getMonths()+"个月，拟至"+a.getProposedEndDate()+"。管理方审核前原合同不变。");return a;
 }
 private LeaseExtensionApplication require(Long id,Long leaseId) {var a=applications.lock(id);if(a==null||!Objects.equals(a.getLeaseId(),leaseId))throw new ResourceNotFoundException("租期延长申请不存在");return a;}
 @Transactional(rollbackFor=Exception.class)
 public void withdraw(Long leaseId,Long id) {
  var lease=access(leaseId,true);resident(lease);var a=require(id,leaseId);
  if("WITHDRAWN".equals(a.getStatus()))return;
  if(!"PENDING".equals(a.getStatus()))throw new BusinessException(ErrorCode.INVALID_PARAM,"仅待审核申请可撤回");
  a.setStatus("WITHDRAWN");applications.updateById(a);history.logAgreement(leaseId,"EXTENSION_WITHDRAWN","居民撤回延长申请"+id);message(lease,"居民已撤回租期延长申请，原期限不变。");
 }
 /* 先锁租约再锁申请：审批重入不再延长；期限/价格任一变化均拒绝旧快照，不能悄改居民方案。 */
 @Transactional(rollbackFor=Exception.class)
 public LeaseExtensionApplication review(Long leaseId,Long id,ReviewLeaseExtensionDTO dto) {
  if(!SecurityUtils.hasRole(RoleConstants.ADMIN)&&!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN))throw new ForbiddenException("仅管理员可审核");
  var lease=access(leaseId,true);var a=require(id,leaseId);
  if((Boolean.TRUE.equals(dto.getApprove())&&"APPROVED".equals(a.getStatus())) || (Boolean.FALSE.equals(dto.getApprove())&&"REJECTED".equals(a.getStatus())))return a;
  if(!"PENDING".equals(a.getStatus()))throw new BusinessException(ErrorCode.INVALID_PARAM,"该申请已经处理");
  if(dto.getApprove()==null)throw new BusinessException(ErrorCode.INVALID_PARAM,"请明确审核结果");
  if(Boolean.TRUE.equals(dto.getApprove())) {
   active(lease);
   if(!lease.getEndDate().equals(a.getOriginalEndDate()) || lease.getMonthlyRent().compareTo(a.getMonthlyRent())!=0 || !sameMoney(lease.getDeposit(),a.getDeposit()))throw new BusinessException(ErrorCode.INVALID_PARAM,"原期限或费用已改变，请驳回后重新申请");
   var t=new LeaseContractTerm();t.setLeaseId(leaseId);t.setTermType("EXTENSION");t.setStartDate(a.getOriginalEndDate().plusDays(1));t.setEndDate(a.getProposedEndDate());t.setContractMonths(a.getMonths());t.setFirstPaymentMonths(1);t.setPaymentCycleMonths(1);t.setMonthlyRent(a.getMonthlyRent());t.setDeposit(BigDecimal.ZERO);t.setStatus("ACTIVE");t.setConfirmedAt(LocalDateTime.now());
   t.setContentSnapshot("租期延长申请#"+a.getId()+"；居民提交已同意，管理方批准即延长。新增"+a.getMonths()+"个月："+t.getStartDate()+"至"+t.getEndDate()+"；沿用月租"+a.getMonthlyRent()+"元，无新增押金；租金按普通缴租支付，不需新增段首笔激活。");terms.insert(t);
   var before=new LeaseRecord();org.springframework.beans.BeanUtils.copyProperties(lease,before);lease.setEndDate(a.getProposedEndDate());leases.updateById(lease);history.logRenew(before,lease,"批准租期延长申请#"+a.getId());a.setStatus("APPROVED");
  } else {if(dto.getReason()==null||dto.getReason().isBlank())throw new BusinessException(ErrorCode.INVALID_PARAM,"驳回须填写原因");a.setStatus("REJECTED");}
  a.setReviewBy(SecurityUtils.getUserId());a.setReviewedAt(LocalDateTime.now());a.setReviewNote(dto.getReason());applications.updateById(a);
  String text="APPROVED".equals(a.getStatus())?"租期延长已批准，合同至"+a.getProposedEndDate()+"；租金照常缴纳，不再收押金。":"租期延长被驳回："+dto.getReason();
  notifications.createForResident(lease.getTenantId(),lease.getCommunityId(),"租期延长审核结果",text,"LEASE","LEASE",leaseId);message(lease,text);return a;
 }
 private boolean sameMoney(BigDecimal a,BigDecimal b) {return a==null?b==null:b!=null&&a.compareTo(b)==0;}
 private void message(LeaseRecord lease,String text) {if(lease.getConversationId()!=null)conversations.sendSystemMessage(lease.getConversationId(),text);}
}
