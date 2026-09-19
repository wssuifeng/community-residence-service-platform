package com.community.residence.reservation.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 可约时段区间覆盖判定（R60 连续时段合并提交）：请求区间 [start, end] 须被当日
 * 可约模板段的并集完整覆盖——相邻相接（prev.end == next.start）可跨多段，
 * 段间存在间隙即不覆盖。资源（C7）与看房（C12）两域共用；
 * 口径与两域 available-slots 周模板展开一致（资源 Slot Grid / 看房 BE-ISSUE-5）。
 */
public final class TimeslotCoverage {

    private TimeslotCoverage() {
    }

    /** 时段段（模板起止） */
    public record Segment(LocalTime start, LocalTime end) {
    }

    /**
     * 判断 [start, end] 是否被时段段并集完整覆盖。实现：段按开始时间升序，
     * 游标自 start 起推进——段起点落后于游标即存在间隙（拒绝），段终点先于
     * 游标的段无贡献（跳过），游标推进到 end 即覆盖成立。
     */
    public static boolean isCovered(List<Segment> segments, LocalTime start, LocalTime end) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparing(Segment::start));
        LocalTime cursor = start;
        for (Segment segment : sorted) {
            if (segment.start().isAfter(cursor)) {
                return false;
            }
            if (segment.end().isAfter(cursor)) {
                cursor = segment.end();
            }
            if (!cursor.isBefore(end)) {
                return true;
            }
        }
        return !cursor.isBefore(end);
    }
}
