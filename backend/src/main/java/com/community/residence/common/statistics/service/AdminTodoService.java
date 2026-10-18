package com.community.residence.common.statistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.resident.constant.ApplicationStatus;
import com.community.residence.common.conversation.constant.ChatMessageType;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.conversation.constant.ConversationStatus;
import com.community.residence.common.conversation.constant.ConversationType;
import com.community.residence.common.payment.constant.DepositRefundStatus;
import com.community.residence.common.lease.constant.DepositStatus;
import com.community.residence.common.workorder.constant.DispatchFlag;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.community.constant.HouseStatusConstant;
import com.community.residence.common.lease.constant.LeaseStatus;
import com.community.residence.common.lease.constant.MoveOutStatus;
import com.community.residence.common.notice.constant.NoticeStatus;
import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.payment.constant.PaymentChannel;
import com.community.residence.common.payment.constant.PaymentEnvironment;
import com.community.residence.common.payment.constant.PaymentStatus;
import com.community.residence.common.payment.constant.PaymentType;
import com.community.residence.common.reservation.constant.ReservationStatus;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.workorder.constant.ShiftType;
import com.community.residence.common.workorder.constant.WorkOrderPriority;
import com.community.residence.common.workorder.constant.WorkOrderProcessAction;
import com.community.residence.common.workorder.constant.WorkOrderStatus;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.feedback.entity.Feedback;
import com.community.residence.common.feedback.mapper.FeedbackMapper;
import com.community.residence.common.reservation.entity.ResourceReservation;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.resident.entity.ResidenceApplication;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.lease.entity.LeaseMoveOutApplication;
import com.community.residence.common.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.housing.entity.ViewingAppointment;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** 待办由实际业务状态计算；社区管理员查询仍经数据级权限拦截，无跨社区数字泄漏。 */
@Service @RequiredArgsConstructor
public class AdminTodoService {
 private final WorkOrderMapper orders;
 private final FeedbackMapper feedback;
 private final ResourceReservationMapper reservations;
 private final ResidenceApplicationMapper applications;
 private final LeaseMoveOutApplicationMapper moveouts;
 private final ViewingAppointmentMapper viewings;
 private final LeaseRecordMapper leases;
 /* 只显示管理端可操作项，不将支付等待算作需管理员处理，也不主动查单。 */
 public Map<String,Long> summary(){
  if(!SecurityUtils.hasRole(RoleConstants.ADMIN)&&!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN))throw new ForbiddenException("仅管理员可查询待办");
  Map<String,Long> result=new LinkedHashMap<>();
  result.put("workOrders",orders.selectCount(new LambdaQueryWrapper<WorkOrder>().in(WorkOrder::getStatus,WorkOrderStatus.PENDING,WorkOrderStatus.TO_ASSIGN,WorkOrderStatus.TO_CONFIRM)));
  result.put("feedbacks",feedback.selectCount(new LambdaQueryWrapper<Feedback>().eq(Feedback::getStatus,"PENDING").or(w->w.eq(Feedback::getStatus,"IN_SESSION").exists("SELECT 1 FROM feedback_message fm WHERE fm.feedback_id = feedback.id AND fm.sender_type = 'RESIDENT' AND fm.id = (SELECT MAX(latest.id) FROM feedback_message latest WHERE latest.feedback_id = feedback.id)"))));
  result.put("reservations",reservations.selectCount(new LambdaQueryWrapper<ResourceReservation>().eq(ResourceReservation::getStatus,ReservationStatus.PENDING)));
  result.put("applications",applications.selectCount(new LambdaQueryWrapper<ResidenceApplication>().eq(ResidenceApplication::getStatus,ApplicationStatus.PENDING)));
  result.put("moveout",moveouts.selectCount(new LambdaQueryWrapper<LeaseMoveOutApplication>().eq(LeaseMoveOutApplication::getStatus,MoveOutStatus.PENDING)));
  result.put("housings",viewings.selectCount(new LambdaQueryWrapper<ViewingAppointment>().eq(ViewingAppointment::getStatus,"TO_CONFIRM")));
  result.put("leases",leases.selectCount(new LambdaQueryWrapper<LeaseRecord>().eq(LeaseRecord::getStatus,LeaseStatus.PENDING).or(w->w.eq(LeaseRecord::getStatus,LeaseStatus.TO_SIGN).and(x->x.in(LeaseRecord::getAgreementStatus,"NONE","CANCELLED").or().isNull(LeaseRecord::getAgreementStatus))).or().exists("SELECT 1 FROM lease_extension_application ex WHERE ex.lease_id=lease_record.id AND ex.status='PENDING'")));
  return result;
 }
}
