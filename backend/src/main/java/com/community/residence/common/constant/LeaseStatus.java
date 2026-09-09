package com.community.residence.common.constant;

/**
 * 租住状态常量（lease_record.status，状态机见架构设计.md §6.2）。
 * FLAG_* 为日期自动判定的到期标注（租期判定任务/到期提醒去重表使用），
 * 非状态机状态值，不参与流转。
 */
public final class LeaseStatus {

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String MOVED_OUT = "MOVED_OUT";
    public static final String ARCHIVED = "ARCHIVED";
    public static final String REJECTED = "REJECTED";

    /** 到期标注：即将到期（止期前 N 天内，R14） */
    public static final String FLAG_EXPIRING = "EXPIRING";
    /** 到期标注：已到期（止期已过，R14） */
    public static final String FLAG_EXPIRED = "EXPIRED";

    private LeaseStatus() {
    }
}
