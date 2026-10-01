package com.couplefinance.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.couplefinance.household.api.BudgetPeriod;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BudgetPeriodCalendarTest {

    @Test
    void BR_HH_05_start_day_1_period_is_the_calendar_month() {
        BudgetPeriod period = BudgetPeriodCalendar.containing(1, LocalDate.of(2026, 2, 17));

        assertThat(period).isEqualTo(new BudgetPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)));
    }

    @Test
    void BR_HH_05_start_day_15_period_runs_15th_to_15th() {
        assertThat(BudgetPeriodCalendar.containing(15, LocalDate.of(2026, 3, 14)))
                .isEqualTo(new BudgetPeriod(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15)));
        assertThat(BudgetPeriodCalendar.containing(15, LocalDate.of(2026, 3, 15)))
                .isEqualTo(new BudgetPeriod(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 15)));
    }

    @Test
    void BR_HH_05_start_day_28_exists_in_february_and_crosses_the_year_boundary() {
        assertThat(BudgetPeriodCalendar.containing(28, LocalDate.of(2026, 2, 28)))
                .isEqualTo(new BudgetPeriod(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 28)));
        assertThat(BudgetPeriodCalendar.containing(28, LocalDate.of(2026, 1, 10)))
                .isEqualTo(new BudgetPeriod(LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 28)));
    }

    @Test
    void BR_HH_05_leap_year_february_bounds() {
        assertThat(BudgetPeriodCalendar.containing(1, LocalDate.of(2028, 2, 29)))
                .isEqualTo(new BudgetPeriod(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 1)));
    }

    @Test
    void BR_HH_05_period_end_date_is_exclusive() {
        BudgetPeriod period = BudgetPeriodCalendar.containing(1, LocalDate.of(2026, 1, 31));

        assertThat(period.contains(LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(period.contains(LocalDate.of(2026, 2, 1))).isFalse();
    }

    @Test
    void BR_HH_07_a_period_shorter_than_15_days_is_extended_to_the_following_occurrence() {
        // Boundary 20 Jan, start day 5: the next 5th is only 16 days later (kept), but from 25 Jan it would be 11.
        BudgetPeriod kept = new BudgetPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20));
        assertThat(BudgetPeriodCalendar.following(kept, 5))
                .isEqualTo(new BudgetPeriod(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 5)));

        BudgetPeriod shortBefore = new BudgetPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 25));
        assertThat(BudgetPeriodCalendar.following(shortBefore, 5))
                .isEqualTo(new BudgetPeriod(LocalDate.of(2026, 1, 25), LocalDate.of(2026, 3, 5)));
    }

    @Test
    void BR_HH_07_missing_periods_continue_after_the_last_stored_one() {
        BudgetPeriod last = new BudgetPeriod(LocalDate.of(2028, 3, 1), LocalDate.of(2028, 4, 1));

        List<BudgetPeriod> missing = BudgetPeriodCalendar.missingPeriods(1, Optional.of(last),
                LocalDate.of(2026, 3, 10));

        assertThat(missing).isEmpty();
        assertThat(BudgetPeriodCalendar.missingPeriods(1, Optional.of(last), LocalDate.of(2026, 3, 10).plusMonths(1)))
                .first().isEqualTo(new BudgetPeriod(LocalDate.of(2028, 4, 1), LocalDate.of(2028, 5, 1)));
    }

    @Test
    void first_period_contains_today_and_the_calendar_covers_24_months() {
        LocalDate today = LocalDate.of(2026, 3, 10);

        List<BudgetPeriod> periods = BudgetPeriodCalendar.missingPeriods(1, Optional.empty(), today);

        assertThat(periods.getFirst().contains(today)).isTrue();
        assertThat(periods.getLast().start()).isEqualTo(LocalDate.of(2028, 3, 1));
        assertThat(periods).hasSize(25);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, -1})
    void start_day_must_be_between_1_and_28(int startDay) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BudgetPeriodCalendar.containing(startDay, LocalDate.of(2026, 1, 1)));
    }

    @Property
    void generated_calendars_are_contiguous_non_overlapping_and_within_length_bounds(
            @ForAll @IntRange(min = 1, max = 28) int startDay,
            @ForAll @IntRange(min = 2000, max = 2100) int year,
            @ForAll @IntRange(min = 1, max = 12) int month,
            @ForAll @IntRange(min = 1, max = 28) int day) {
        LocalDate today = LocalDate.of(year, month, day);

        List<BudgetPeriod> periods = BudgetPeriodCalendar.missingPeriods(startDay, Optional.empty(), today);

        assertThat(periods.getFirst().contains(today)).isTrue();
        assertThat(periods.getFirst().start().getDayOfMonth()).isEqualTo(startDay);
        for (int i = 0; i < periods.size(); i++) {
            BudgetPeriod period = periods.get(i);
            long days = period.end().toEpochDay() - period.start().toEpochDay();
            assertThat(days).isBetween(15L, 62L);
            if (i > 0) {
                assertThat(period.start()).isEqualTo(periods.get(i - 1).end());
            }
        }
        assertThat(periods.getLast().start()).isBefore(today.plusMonths(BudgetPeriodCalendar.MONTHS_AHEAD));
        assertThat(periods.getLast().end()).isAfterOrEqualTo(today.plusMonths(BudgetPeriodCalendar.MONTHS_AHEAD));
    }

    @Property
    void extending_in_two_steps_equals_generating_at_once(
            @ForAll @IntRange(min = 1, max = 28) int startDay,
            @ForAll @IntRange(min = 0, max = 400) int laterDays) {
        LocalDate today = LocalDate.of(2026, 5, 20);
        List<BudgetPeriod> first = BudgetPeriodCalendar.missingPeriods(startDay, Optional.empty(), today);
        List<BudgetPeriod> extension = BudgetPeriodCalendar.missingPeriods(startDay,
                Optional.of(first.getLast()), today.plusDays(laterDays));
        List<BudgetPeriod> all = new java.util.ArrayList<>(first);
        all.addAll(extension);

        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).start()).isEqualTo(all.get(i - 1).end());
        }
    }
}
