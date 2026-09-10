package com.community.residence.schedule.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.schedule.service.TaskLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 操作日志留存清理任务（R58，每日 03:00，避开既有 01:00/01:30 任务）。
 * 清理 sys_operation_log 中 created_at 早于（当前时间 - 保留阈值）的记录，
 * 阈值取 sys_config: log.retention_days（默认 730 天，非法值回退默认并 WARN）；
 * 分批 DELETE 避免单事务过大；待删为 0 时跳过且不通知；
 * 实际清理后经通知中心通知全部 SUPER_ADMIN（R58：杜绝运营者不知情的数据清理），
 * 清理动作本身写 sys_task_log（含清理条数统计）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRetentionCleanTask {

    public static final String TASK_NAME = "LogRetentionCleanTask";
    public static final String KEY_LOG_RETENTION_DAYS = "log.retention_days";
    private static final String LOCK_KEY = "schedule:lock:LogRetentionCleanTask";
    private static final int DEFAULT_RETENTION_DAYS = 730;
    private static final int BATCH_SIZE = 1000;

    private final SysOperationLogMapper operationLogMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final SysConfigService sysConfigService;
    private final TaskLogService taskLogService;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 0 3 * * *")
    public void execute() {
        LocalDateTime start = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        String errorMessage = null;
        int cleaned = 0;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.info("日志留存清理：已有实例持锁执行，本次跳过");
                return;
            }
            int retentionDays = resolveRetentionDays();
            LocalDateTime deadline = LocalDateTime.now().minusDays(retentionDays);
            long pending = operationLogMapper.selectCount(
                    new LambdaQueryWrapper<SysOperationLog>()
                            .lt(SysOperationLog::getCreatedAt, deadline));
            if (pending == 0) {
                log.info("日志留存清理：无到期日志（阈值 {} 天），跳过", retentionDays);
            } else {
                cleaned = cleanBefore(deadline);
                log.info("日志留存清理完成：阈值 {} 天，待删 {} 条，实际清理 {} 条",
                        retentionDays, pending, cleaned);
                notifySuperAdmins(cleaned, retentionDays);
            }
        } catch (Exception e) {
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("日志留存清理任务失败", e);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
            taskLogService.record(TASK_NAME, start, System.currentTimeMillis() - t0,
                    cleaned, cleaned, 0, errorMessage);
        }
    }

    /* 分批删除：每批按 id 升序取 BATCH_SIZE 条到期记录后删除，避免单事务过大 */
    private int cleanBefore(LocalDateTime deadline) {
        int total = 0;
        while (true) {
            List<SysOperationLog> batch = operationLogMapper.selectList(
                    new LambdaQueryWrapper<SysOperationLog>()
                            .lt(SysOperationLog::getCreatedAt, deadline)
                            .orderByAsc(SysOperationLog::getId)
                            .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            total += operationLogMapper.delete(new LambdaQueryWrapper<SysOperationLog>()
                    .in(SysOperationLog::getId,
                            batch.stream().map(SysOperationLog::getId).toList()));
        }
        return total;
    }

    /* R58 核心：清理动作对运营者透明——通知全部超级管理员（标题含条数与阈值，可溯源） */
    private void notifySuperAdmins(int cleaned, int retentionDays) {
        List<SysUser> superAdmins = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, "SUPER_ADMIN")
                        .eq(SysUser::getStatus, "ACTIVE"));
        for (SysUser admin : superAdmins) {
            notificationService.create(admin.getId(), null,
                    "操作日志留存清理完成",
                    "系统已自动清理 " + cleaned + " 条到期操作日志（保留阈值 "
                            + retentionDays + " 天，配置项 log.retention_days）。",
                    "SYSTEM", "TASK", null);
        }
    }

    private int resolveRetentionDays() {
        String value = sysConfigService.getValue(KEY_LOG_RETENTION_DAYS);
        try {
            return value != null ? Integer.parseInt(value) : DEFAULT_RETENTION_DAYS;
        } catch (NumberFormatException e) {
            log.warn("配置 {} 值非法：{}，回退默认 {} 天",
                    KEY_LOG_RETENTION_DAYS, value, DEFAULT_RETENTION_DAYS);
            return DEFAULT_RETENTION_DAYS;
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
