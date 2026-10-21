package com.community.residence.common.statistics.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StatisticsDateRangeTest {

    @Test
    void missingDatesDefaultToTheLastThirtyInclusiveCalendarDays() {
        LocalDate today = LocalDate.of(2026, 10, 20);

        StatisticsDateRange range = StatisticsDateRange.resolve(null, null, today);

        assertThat(range.startDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(range.endDate()).isEqualTo(today);
        assertThat(range.endExclusive()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(range.days()).isEqualTo(30);
    }

    @Test
    void suppliedBoundsAreInclusiveAndMissingEndDefaultsToToday() {
        LocalDate today = LocalDate.of(2026, 10, 20);

        StatisticsDateRange range = StatisticsDateRange.resolve(LocalDate.of(2026, 10, 1), null, today);

        assertThat(range.startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(range.endExclusive()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(range.days()).isEqualTo(20);
    }

    @Test
    void rejectsReversedBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> StatisticsDateRange.resolve(
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20)));
    }
}
