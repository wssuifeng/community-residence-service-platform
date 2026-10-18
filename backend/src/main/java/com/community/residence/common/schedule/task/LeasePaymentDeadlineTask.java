package com.community.residence.common.schedule.task;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.common.lease.service.LeasePaymentDeadlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
/** 本地期限任务：只处理本地单据，不调用渠道；逐租约事务及锁保证重入。 */
@Component @RequiredArgsConstructor @Slf4j
public class LeasePaymentDeadlineTask {
    private final LeaseRecordMapper leases;
    private final LeasePaymentMapper payments;
    private final LeasePaymentDeadlineService service;
    @Scheduled(cron="0 */5 * * * *")
    public void execute(){
        var now=LocalDateTime.now();
        for(var lease:leases.selectList(new LambdaQueryWrapper<LeaseRecord>().in(LeaseRecord::getStatus,"PENDING","TO_SIGN").lt(LeaseRecord::getStartDate,now.toLocalDate()))){
            try{
                service.rejectUnpaid(lease.getId(),now);
            }
            catch(Exception e){
                log.error("未付首期自动驳回失败 leaseId={}",lease.getId(),e);
            }
        }
        for(var lease:leases.selectList(new LambdaQueryWrapper<LeaseRecord>().eq(LeaseRecord::getStatus,"ACTIVE").le(LeaseRecord::getStartDate,now.toLocalDate()))){
            try{
                service.beginResidence(lease.getId(),now);
            }
            catch(Exception e){
                log.error("计划入住日期处理失败 leaseId={}",lease.getId(),e);
            }
        }
        for(var order:payments.selectList(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getStatus,"PENDING").le(LeasePayment::getExpiresAt,now))){
            try{
                service.closeExpired(order.getId(),now);
            }
            catch(Exception e){
                log.error("本地支付期限处理失败 paymentId={}",order.getId(),e);
            }
        }
    }
}
