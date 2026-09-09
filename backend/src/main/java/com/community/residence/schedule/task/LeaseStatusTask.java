package com.community.residence.schedule.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.schedule.service.TaskLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 租期判定扫描（R14，每日 01:00）。
 * 「即将到期/已到期」为日期自动判定标注、非状态流转（架构设计.md §6.2），
 * 判定结果在租住查询时按止期+当前日期实时计算，本任务不做任何状态写回，
 * 仅扫描统计 ACTIVE 租住中两档标注的规模并落任务日志，供运维观测。
 * 配置 N（即将到期窗口天数）取 sys_config: lease.reminder_days_before_expire。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseStatusTask {

    public static final String TASK_NAME = "LeaseStatusTask";
    private static final String LOCK_KEY = "schedule:lock:LeaseStatusTask";
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final LeaseRecordMapper leaseRecordMapper;
    private final SysConfigService sysConfigService;
    private final TaskLogService taskLogService;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 0 1 * * *")
    public void execute() {
        LocalDateTime start = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        String errorMessage = null;
        long expiredCount = 0;
        long expiringCount = 0;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.info("租期判定扫描：已有实例持锁执行，本次跳过");
                return;
            }
            int windowDays = resolveWindowDays();
            LocalDate today = LocalDate.now();
            expiredCount = leaseRecordMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                    .eq(LeaseRecord::getStatus, LeaseStatus.ACTIVE)
                    .lt(LeaseRecord::getEndDate, today));
            expiringCount = leaseRecordMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                    .eq(LeaseRecord::getStatus, LeaseStatus.ACTIVE)
                    .between(LeaseRecord::getEndDate, today, today.plusDays(windowDays)));
            log.info("租期判定扫描完成：ACTIVE 租住中已到期 {} 条、{} 天内即将到期 {} 条",
                    expiredCount, windowDays, expiringCount);
        } catch (Exception e) {
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("租期判定扫描失败", e);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
            int processed = (int) (expiredCount + expiringCount);
            taskLogService.record(TASK_NAME, start, System.currentTimeMillis() - t0,
                    processed, processed, 0, errorMessage);
        }
    }

    private int resolveWindowDays() {
        String value = sysConfigService.getValue(SysConfigService.KEY_LEASE_REMINDER_DAYS);
        try {
            return value != null ? Integer.parseInt(value) : DEFAULT_WINDOW_DAYS;
        } catch (NumberFormatException e) {
            log.warn("配置 {} 值非法：{}，回退默认 {} 天",
                    SysConfigService.KEY_LEASE_REMINDER_DAYS, value, DEFAULT_WINDOW_DAYS);
            return DEFAULT_WINDOW_DAYS;
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (Exception e) {
            log.warn("分布式锁释放异常（锁键已过期时正常）", e);
        }
    }
}
