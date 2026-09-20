package com.community.residence.common.constant;

import java.time.LocalTime;

/**
 * 服务人员班次常量（staff_schedule.shift_type，V19）。
 * 班次默认起止时间为落库快照：管理员传自定义时间时以自定义为准，
 * REST（休息）无起止时间（start/end 为空，与「未排班」区分）。
 * 权威口径见 docs/30_系统设计/数据库设计.md 与 V19 迁移脚本注释。
 */
public final class ShiftType {

    public static final String MORNING = "MORNING";
    public static final String AFTERNOON = "AFTERNOON";
    public static final String EVENING = "EVENING";
    public static final String FULL = "FULL";
    public static final String REST = "REST";

    private static final LocalTime MORNING_START = LocalTime.of(8, 0);
    private static final LocalTime MORNING_END = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);
    private static final LocalTime EVENING_START = LocalTime.of(18, 0);
    private static final LocalTime EVENING_END = LocalTime.of(22, 0);
    private static final LocalTime FULL_START = LocalTime.of(8, 0);
    private static final LocalTime FULL_END = LocalTime.of(18, 0);

    private ShiftType() {
    }

    /** 是否为合法班次取值 */
    public static boolean isValid(String shiftType) {
        return MORNING.equals(shiftType) || AFTERNOON.equals(shiftType) || EVENING.equals(shiftType)
                || FULL.equals(shiftType) || REST.equals(shiftType);
    }

    /** 班次默认开始时间；REST 无起止时间返回 null */
    public static LocalTime defaultStart(String shiftType) {
        return switch (shiftType == null ? "" : shiftType) {
            case MORNING -> MORNING_START;
            case AFTERNOON -> AFTERNOON_START;
            case EVENING -> EVENING_START;
            case FULL -> FULL_START;
            default -> null;
        };
    }

    /** 班次默认结束时间；REST 无起止时间返回 null */
    public static LocalTime defaultEnd(String shiftType) {
        return switch (shiftType == null ? "" : shiftType) {
            case MORNING -> MORNING_END;
            case AFTERNOON -> AFTERNOON_END;
            case EVENING -> EVENING_END;
            case FULL -> FULL_END;
            default -> null;
        };
    }

    /** 班次中文标签（前端展示用；未知取值原样返回） */
    public static String label(String shiftType) {
        return switch (shiftType == null ? "" : shiftType) {
            case MORNING -> "早班";
            case AFTERNOON -> "午班";
            case EVENING -> "晚班";
            case FULL -> "全天";
            case REST -> "休息";
            default -> shiftType;
        };
    }
}
