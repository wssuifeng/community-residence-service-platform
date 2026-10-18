package com.community.residence.common.messaging.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 实时投递与成功提交绑定；无事务直接投递，失败由既有拉取链补偿。 */
@Slf4j
@Service
public class AfterCommitDeliveryService {
    /* 回调只持有已经固定的目的地和载荷，不在提交后继续查询或写业务记录。 */
    public void deliver(String destination, Runnable send) {
        Runnable guarded = () -> {
            try { send.run(); }
            catch (Exception e) { log.warn("实时投递失败，由拉取补偿：destination={}", destination, e); }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { guarded.run(); }
            });
        } else {
            guarded.run();
        }
    }
}
