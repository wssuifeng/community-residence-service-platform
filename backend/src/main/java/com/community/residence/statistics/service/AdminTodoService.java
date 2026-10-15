package com.community.residence.statistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.*;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.mapper.FeedbackMapper;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.resident.entity.ResidenceApplication;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.lease.entity.LeaseMoveOutApplication;
import com.community.residence.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
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
  result.put("leases",leases.selectCount(new LambdaQueryWrapper<LeaseRecord>().eq(LeaseRecord::getStatus,LeaseStatus.PENDING).or(w->w.eq(LeaseRecord::getStatus,LeaseStatus.TO_SIGN).and(x->x.in(LeaseRecord::getAgreementStatus,"NONE","CANCELLED").or().isNull(LeaseRecord::getAgreementStatus)))));
  return result;
 }
}
