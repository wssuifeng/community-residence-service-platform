package com.community.residence.lease.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.payment.entity.LeasePayment;
import com.community.residence.payment.mapper.LeasePaymentMapper;
import com.community.residence.resident.entity.ResidenceApplication;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
@Service @RequiredArgsConstructor
public class LeasePaymentDeadlineService {
    private final LeaseService leaseService;
    private final LeaseRecordMapper leases;
    private final LeasePaymentMapper payments;
    private final ResidenceApplicationMapper applications;
    private final com.community.residence.community.mapper.HouseMapper houses;
    private final com.community.residence.resident.mapper.ResidenceRelationMapper relations;
    /* 与结算使用相同房屋→租约→支付锁；按订单保存的本地截止处理（新单为渠道截止后30秒），已付款不能被驳回。 */
    @Transactional(rollbackFor=Exception.class)
    public boolean rejectUnpaid(Long leaseId,LocalDateTime now){
        var lease=leaseService.lockForSettlement(leaseId);
        if(!java.util.Set.of("PENDING","TO_SIGN").contains(lease.getStatus())||lease.getStartDate()==null||!lease.getStartDate().isBefore(now.toLocalDate()))return false;
        if(now.isBefore(LeaseTermRules.localDeadline(lease.getStartDate().plusDays(1).atStartOfDay())))return false;
        var orders=payments.selectList(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getLeaseId,leaseId).eq(LeasePayment::getPaymentType,"FIRST"));
        for(var order:orders){
            var current=payments.selectByIdForUpdate(order.getId());
            if(current==null)continue;
            if("SUCCESS".equals(current.getStatus()))return false;
            if(current.getSettlementIssue()!=null)return false;
            if("PENDING".equals(current.getStatus())&&current.getExpiresAt()!=null&&current.getExpiresAt().isAfter(now))return false;
        }
        for(var order:orders)if("PENDING".equals(order.getStatus())){
            order.setStatus("CLOSED");
            payments.updateById(order);
        }
        String reason="已过计划起租日且首期费用未支付，系统自动驳回，请重新申请";
        applications.update(null,new LambdaUpdateWrapper<ResidenceApplication>().eq(ResidenceApplication::getLeaseId,leaseId).in(ResidenceApplication::getStatus,"PENDING","APPROVED").set(ResidenceApplication::getStatus,"REJECTED").set(ResidenceApplication::getReviewRemark,reason).set(ResidenceApplication::getReviewTime,now));
        leaseService.applyTransition(lease,"REJECTED",reason);
        return true;
    }
    @Transactional(rollbackFor=Exception.class)
    public void closeExpired(Long id,LocalDateTime now){
        var payment=payments.selectByIdForUpdate(id);
        if(payment!=null&&"PENDING".equals(payment.getStatus())&&payment.getExpiresAt()!=null&&!payment.getExpiresAt().isAfter(now)){
            payment.setStatus("CLOSED");
            payments.updateById(payment);
        }
    }
    @Transactional(rollbackFor=Exception.class)
    public void beginResidence(Long leaseId,LocalDateTime now){
        var lease=leaseService.lockForSettlement(leaseId);
        if(!"ACTIVE".equals(lease.getStatus())||lease.getStartDate()==null||lease.getStartDate().isAfter(now.toLocalDate()))return;
        if(relations.selectCount(new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceRelation>().eq(com.community.residence.resident.entity.ResidenceRelation::getLeaseId,leaseId).isNull(com.community.residence.resident.entity.ResidenceRelation::getMoveOutDate))<1)return;
        var house=houses.selectById(lease.getHouseId());
        if(house!=null&&"RESERVED".equals(house.getStatus())){
            house.setStatus("OCCUPIED");
            houses.updateById(house);
        }
    }
}
