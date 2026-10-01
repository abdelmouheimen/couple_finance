package com.couplefinance.household.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.couplefinance.household.api.BudgetPeriod;

/**
 * Pure period arithmetic (BR-HH-05, BR-HH-07). A period starts on the start day (1-28, so it exists in every
 * month) and ends on its next occurrence; a period shorter than {@value #MIN_DAYS} days is extended to the
 * following occurrence. Generated calendars are contiguous and never overlap.
 */
public final class BudgetPeriodCalendar {

    /** Calendar horizon: periods starting before today + this many months exist (database-schema.md §5.5). */
    public static final int MONTHS_AHEAD = 24;

    static final int MIN_DAYS = 15;

    private BudgetPeriodCalendar() {
    }

    /** The period containing {@code date} for a calendar with the given start day. */
    public static BudgetPeriod containing(int startDay, LocalDate date) {
        requireStartDay(startDay);
        LocalDate start = date.getDayOfMonth() >= startDay
                ? date.withDayOfMonth(startDay)
                : date.minusMonths(1).withDayOfMonth(startDay);
        return new BudgetPeriod(start, endOf(start, startDay));
    }

    /** The period starting where {@code previous} ends. */
    public static BudgetPeriod following(BudgetPeriod previous, int startDay) {
        requireStartDay(startDay);
        return new BudgetPeriod(previous.end(), endOf(previous.end(), startDay));
    }

    /**
     * The periods to store so that the calendar covers {@code today} and {@link #MONTHS_AHEAD} months ahead:
     * those following {@code last} (or beginning with the period containing {@code today} when nothing is stored)
     * whose start is before the horizon. Empty when the calendar is already long enough.
     */
    public static List<BudgetPeriod> missingPeriods(int startDay, Optional<BudgetPeriod> last, LocalDate today) {
        LocalDate horizon = today.plusMonths(MONTHS_AHEAD);
        List<BudgetPeriod> missing = new ArrayList<>();
        BudgetPeriod next = last.map(period -> following(period, startDay))
                .orElseGet(() -> containing(startDay, today));
        while (next.start().isBefore(horizon)) {
            missing.add(next);
            next = following(next, startDay);
        }
        return missing;
    }

    private static LocalDate endOf(LocalDate start, int startDay) {
        LocalDate end = nextOccurrenceAfter(start, startDay);
        if (end.toEpochDay() - start.toEpochDay() < MIN_DAYS) {
            end = nextOccurrenceAfter(end, startDay);
        }
        return end;
    }

    private static LocalDate nextOccurrenceAfter(LocalDate date, int startDay) {
        return date.getDayOfMonth() < startDay
                ? date.withDayOfMonth(startDay)
                : date.plusMonths(1).withDayOfMonth(startDay);
    }

    private static void requireStartDay(int startDay) {
        if (startDay < PeriodRule.MIN_START_DAY || startDay > PeriodRule.MAX_START_DAY) {
            throw new IllegalArgumentException("Period start day must be between 1 and 28: " + startDay);
        }
    }
}
