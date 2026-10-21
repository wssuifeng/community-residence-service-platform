package com.community.residence.common.statistics.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Inclusive calendar-date range shared by management statistics endpoints. */
public record StatisticsDateRange(LocalDate startDate, LocalDate endDate) {

    private static final int DEFAULT_DAYS = 30;

    public static StatisticsDateRange resolve(LocalDate startDate, LocalDate endDate, LocalDate today) {
        LocalDate resolvedEnd = endDate == null ? today : endDate;
        LocalDate resolvedStart = startDate == null
                ? resolvedEnd.minusDays(DEFAULT_DAYS - 1L)
                : startDate;
        if (resolvedStart.isAfter(resolvedEnd)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        return new StatisticsDateRange(resolvedStart, resolvedEnd);
    }

    public LocalDate endExclusive() {
        return endDate.plusDays(1);
    }

    public int days() {
        return Math.toIntExact(ChronoUnit.DAYS.between(startDate, endDate) + 1L);
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
