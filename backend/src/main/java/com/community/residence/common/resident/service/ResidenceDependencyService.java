package com.community.residence.common.resident.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;

/** 依赖只在关系层建立；登记及结束均由调用方在房屋锁和事务内执行。 */
@Service
@RequiredArgsConstructor
public class ResidenceDependencyService {
 private final ResidenceRelationMapper relationMapper;
 public static boolean isDependent(String type) { return "FAMILY".equals(type) || "CO_RESIDENT".equals(type); }
 public List<ResidenceRelation> activeMembers(Long anchorId) {
  return relationMapper.selectList(new LambdaQueryWrapper<ResidenceRelation>().eq(ResidenceRelation::getAnchorRelationId,anchorId).isNull(ResidenceRelation::getMoveOutDate).orderByAsc(ResidenceRelation::getId));
 }
 /* 未明确确认时拒绝整户结束；校验所有日期后再写，不能让末位成员失败造成部分迁出。 */
 public List<ResidenceRelation> endMembers(Long anchorId, LocalDate date, boolean confirmed) {
  var members=relationMapper.selectList(new LambdaQueryWrapper<ResidenceRelation>()
    .eq(ResidenceRelation::getAnchorRelationId,anchorId).isNull(ResidenceRelation::getMoveOutDate)
    .orderByAsc(ResidenceRelation::getId).last("FOR UPDATE"));
  if (!members.isEmpty() && !confirmed) throw new BusinessException(ErrorCode.INVALID_PARAM,"该主居住关系关联"+members.size()+"名同住人员，请确认同步结束依赖关系");
  for(var member:members) if(date==null || member.getMoveInDate()!=null && date.isBefore(member.getMoveInDate())) throw new BusinessException(ErrorCode.INVALID_PARAM,"迁出日期不能早于任何同住人员的入住日期");
  for(var member:members){member.setMoveOutDate(date);relationMapper.updateById(member);}
  return members;
 }
}
