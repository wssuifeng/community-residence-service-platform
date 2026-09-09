package com.community.residence.schedule.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.entity.LeaseReminder;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.mapper.LeaseReminderMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.schedule.service.TaskLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 租期到期提醒（R15，每日 01:30，租期判定扫描之后）。
 * 对止期进入提醒窗口的 ACTIVE 租住记录生成站内通知，通知居民与绑定社区管理员；
 * 去重由 lease_reminder 唯一键（lease_id + status）保证——同一租住记录同一到期标注
 * 仅提醒一次，插入冲突即视为已提醒过直接跳过；提醒窗口天数取
 * sys_config: lease.reminder_days_before_expire（R15 可配）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseExpiryReminderTask {

    public static final String TASK_NAME = "LeaseExpiryReminderTask";
    private static final String LOCK_KEY = "schedule:lock:LeaseExpiryReminderTask";
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final LeaseRecordMapper leaseRecordMapper;
    private final LeaseReminderMapper leaseReminderMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final NotificationService notificationService;
    private final SysConfigService sysConfigService;
    private final TaskLogService taskLogService;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 30 1 * * *")
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
                log.info("租期到期提醒：已有实例持锁执行，本次跳过");
                return;
            }
            int windowDays = resolveWindowDays();
            LocalDate today = LocalDate.now();
            /* 窗口含即将到期（today ~ today+N）与已到期（< today）两档，覆盖未搬出的 ACTIVE 租住 */
            List<LeaseRecord> leases = leaseRecordMapper.selectList(
                    new LambdaQueryWrapper<LeaseRecord>()
                            .eq(LeaseRecord::getStatus, LeaseStatus.ACTIVE)
                            .le(LeaseRecord::getEndDate, today.plusDays(windowDays)));
            for (LeaseRecord lease : leases) {
                processed++;
                try {
                    if (remind(lease, today)) {
                        success++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("租期到期提醒失败：leaseId={}", lease.getId(), e);
                }
            }
            log.info("租期到期提醒完成：扫描 {} 条，新发提醒 {} 条，失败 {} 条", processed, success, failed);
        } catch (Exception e) {
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("租期到期提醒任务失败", e);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
            taskLogService.record(TASK_NAME, start, System.currentTimeMillis() - t0,
                    processed, success, failed, errorMessage);
        }
    }

    /**
     * 对单条租住记录发起到期提醒。
     *
     * @return true = 本次新生成提醒；false = 唯一键冲突，此前已提醒过
     */
    private boolean remind(LeaseRecord lease, LocalDate today) {
        String flag = lease.getEndDate().isBefore(today)
                ? LeaseStatus.FLAG_EXPIRED : LeaseStatus.FLAG_EXPIRING;
        LeaseReminder reminder = new LeaseReminder();
        reminder.setLeaseId(lease.getId());
        reminder.setStatus(flag);
        reminder.setRemindTime(LocalDateTime.now());
        try {
            leaseReminderMapper.insert(reminder);
        } catch (DuplicateKeyException e) {
            return false;
        }

        boolean expired = LeaseStatus.FLAG_EXPIRED.equals(flag);
        String dateText = lease.getEndDate().toString();
        notificationService.create(lease.getTenantId(), lease.getCommunityId(),
                expired ? "租住已到期" : "租住即将到期",
                expired
                        ? "您的租住合同已于 " + dateText + " 到期，请尽快办理续签或退住手续。"
                        : "您的租住合同将于 " + dateText + " 到期，请及时续签或办理退住手续。",
                "LEASE", "LEASE_RECORD", lease.getId());
        for (Long adminId : boundAdminIds(lease.getCommunityId())) {
            notificationService.create(adminId, lease.getCommunityId(),
                    expired ? "社区租住已到期" : "社区租住即将到期",
                    "租住记录 #" + lease.getId() + " 的合同"
                            + (expired ? "已于 " : "将于 ") + dateText
                            + (expired ? " 到期，请跟进居民办理退住。" : " 到期，请跟进居民续签或退住。"),
                    "LEASE", "LEASE_RECORD", lease.getId());
        }
        return true;
    }

    private List<Long> boundAdminIds(Long communityId) {
        return sysAdminCommunityMapper.selectList(new LambdaQueryWrapper<SysAdminCommunity>()
                        .eq(SysAdminCommunity::getCommunityId, communityId))
                .stream().map(SysAdminCommunity::getAdminId).toList();
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
