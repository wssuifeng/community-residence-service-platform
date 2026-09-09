package com.community.residence.schedule.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.residence.common.constant.NoticeStatus;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.schedule.service.TaskLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 公告自动下线（R26，每小时整点）。
 * 公告状态机无 EXPIRED 态（DRAFT/PUBLISHED/WITHDRAWN，架构设计.md §6），
 * 失效时间已过的 PUBLISHED 公告批量置为 WITHDRAWN 视同下线；
 * 居民端可见性另有查询期时间过滤兜底（NoticeService.isExpired），
 * 下线前窗口期（≤1 小时）不出现过期公告。UPDATE WHERE 含状态判断保证幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeAutoOfflineTask {

    public static final String TASK_NAME = "NoticeAutoOfflineTask";
    private static final String LOCK_KEY = "schedule:lock:NoticeAutoOfflineTask";

    private final NoticeMapper noticeMapper;
    private final TaskLogService taskLogService;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 0 * * * *")
    public void execute() {
        LocalDateTime start = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        String errorMessage = null;
        int offlineCount = 0;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.info("公告自动下线：已有实例持锁执行，本次跳过");
                return;
            }
            offlineCount = noticeMapper.update(null, new LambdaUpdateWrapper<Notice>()
                    .eq(Notice::getStatus, NoticeStatus.PUBLISHED)
                    .isNotNull(Notice::getEndTime)
                    .lt(Notice::getEndTime, LocalDateTime.now())
                    .set(Notice::getStatus, NoticeStatus.WITHDRAWN));
            if (offlineCount > 0) {
                log.info("公告自动下线完成：{} 条过期公告置为 WITHDRAWN", offlineCount);
            }
        } catch (Exception e) {
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("公告自动下线任务失败", e);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
            taskLogService.record(TASK_NAME, start, System.currentTimeMillis() - t0,
                    offlineCount, offlineCount, 0, errorMessage);
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
