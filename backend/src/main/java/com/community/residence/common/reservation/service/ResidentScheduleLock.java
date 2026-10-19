package com.community.residence.common.reservation.service;

import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 同居民同日的资源预约及看房共用互斥，跨资源/跨域检查必须在同一锁内。 */
@Slf4j
public final class ResidentScheduleLock {
    private ResidentScheduleLock() { }

    /* 居民日程锁先于房屋行锁及资源日期锁获取；有事务时提交/回滚后才释放。
       无事务资源预约为单次INSERT自动提交，返回前释放；锁故障不能降级放行。 */
    public static <T> T execute(RedissonClient client, Long residentId, LocalDate date, Supplier<T> action) {
        RLock lock = client.getLock("reservation:lock:resident:" + residentId + ":" + date);
        boolean locked = false;
        boolean deferred = false;
        try {
            try { locked = lock.tryLock(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "预约请求繁忙，请稍后重试");
            }
            if (!locked) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "预约请求繁忙，请稍后重试");
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { release(lock); }
                });
                deferred = true;
            }
            return action.get();
        } finally {
            if (locked && !deferred) release(lock);
        }
    }

    private static void release(RLock lock) {
        try { lock.unlock(); }
        catch (Exception e) { log.warn("居民日程锁释放失败，等待看门狗/TTL处理", e); }
    }
}
