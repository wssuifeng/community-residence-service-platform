package com.community.residence.schedule.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.service.HousingService;
import com.community.residence.schedule.service.TaskLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 房源浏览统计回写（每 5 分钟）。
 * 浏览请求只 INCR Redis 计数器（HousingService.recordView），本任务批量
 * 将计数累加回 housing.view_count 并删除计数键（架构设计.md §3.3.1）：
 * getAndDelete 原子取值避免 INCR/回写竞态丢计数；回写失败时把计数加回
 * Redis 键，key 保留待下次重试。房源已删除则计数直接丢弃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HousingViewFlushTask {

    public static final String TASK_NAME = "HousingViewFlushTask";
    private static final String LOCK_KEY = "schedule:lock:HousingViewFlushTask";

    private final HousingMapper housingMapper;
    private final StringRedisTemplate redisTemplate;
    private final TaskLogService taskLogService;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 */5 * * * *")
    public void execute() {
        LocalDateTime start = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        String errorMessage = null;
        int processed = 0;
        int success = 0;
        int failed = 0;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.info("浏览统计回写：已有实例持锁执行，本次跳过");
                return;
            }
            try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                    .match(HousingService.VIEW_COUNT_KEY_PREFIX + "*").count(500).build())) {
                while (cursor.hasNext()) {
                    processed++;
                    if (flushOne(cursor.next())) {
                        success++;
                    } else {
                        failed++;
                    }
                }
            }
            if (processed > 0) {
                log.info("浏览统计回写完成：{} 个房源计数键，成功 {}，失败 {}", processed, success, failed);
            }
        } catch (Exception e) {
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("浏览统计回写任务失败", e);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
            taskLogService.record(TASK_NAME, start, System.currentTimeMillis() - t0,
                    processed, success, failed, errorMessage);
        }
    }

    /**
     * 回写单个房源计数键。单键异常不中断整批：把计数还原回 Redis 键待下次重试。
     *
     * @return true = 回写成功或计数可安全丢弃；false = 回写失败，计数已还原回 Redis
     */
    private boolean flushOne(String key) {
        String deltaText = redisTemplate.opsForValue().getAndDelete(key);
        if (deltaText == null) {
            return true;
        }
        long delta;
        try {
            delta = Long.parseLong(deltaText);
        } catch (NumberFormatException e) {
            log.warn("浏览计数键值非法，丢弃：key={}, value={}", key, deltaText);
            return true;
        }
        if (delta <= 0) {
            return true;
        }
        Long housingId = parseHousingId(key);
        if (housingId == null) {
            return true;
        }
        try {
            int rows = housingMapper.update(null, new LambdaUpdateWrapper<Housing>()
                    .eq(Housing::getId, housingId)
                    .setSql("view_count = view_count + {0}", delta));
            if (rows == 0) {
                /* 房源已删除：计数无归宿，丢弃 */
                log.info("房源已删除，浏览计数丢弃：housingId={}, delta={}", housingId, delta);
            }
            return true;
        } catch (Exception e) {
            log.error("浏览计数回写失败，计数还原回 Redis 待重试：key={}, delta={}", key, delta, e);
            redisTemplate.opsForValue().increment(key, delta);
            return false;
        }
    }

    private Long parseHousingId(String key) {
        try {
            return Long.parseLong(key.substring(HousingService.VIEW_COUNT_KEY_PREFIX.length()));
        } catch (NumberFormatException e) {
            log.warn("浏览计数键名非法，丢弃：key={}", key);
            return null;
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
