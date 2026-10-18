package com.community.residence.common.lease.constant;

import java.time.LocalDate;

/**
 * 租住状态常量（lease_record.status，状态机见架构设计.md §6.2）。
 * FLAG_* 为日期自动判定的到期标注（租期判定任务/到期提醒去重表使用），
 * 非状态机状态值，不参与流转。
 */
public final class LeaseStatus {

    public static final String PENDING = "PENDING";

    /** 待签约（R66 v1.8 新增）：入住申请审核通过后的中间态，协议双方确认才置已生效 */
    public static final String TO_SIGN = "TO_SIGN";

    public static final String ACTIVE = "ACTIVE";
    public static final String MOVED_OUT = "MOVED_OUT";
    public static final String ARCHIVED = "ARCHIVED";
    public static final String REJECTED = "REJECTED";

    /** 到期标注：即将到期（止期前 N 天内，R14） */
    public static final String FLAG_EXPIRING = "EXPIRING";
    /** 到期标注：已到期（止期已过，R14） */
    public static final String FLAG_EXPIRED = "EXPIRED";

    /**
     * 已到期判定（唯一口径出处）：止期**严格早于**当天即已到期；
     * **当天到期不算已到期**（仍落在「即将到期」窗口内）。
     * 与下列既有实现逐字一致，改动须三处同改：
     * {@code LeaseService#toVO}（expiryFlag=EXPIRED）、
     * {@code LeaseStatusTask}（{@code lt(end_date, today)} 统计）、
     * {@code LeaseExpiryReminderTask}（{@code endDate.isBefore(today)} 提醒），
     * 前端 {@code LeaseListView.expiryOf}（{@code days < 0}）同口径。
     */
    public static boolean isExpired(LocalDate endDate, LocalDate today) {
        return endDate != null && today != null && endDate.isBefore(today);
    }

    private LeaseStatus() {
    }
}
