package com.couplefinance.household.api;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A budget period: the range of expense dates {@code [start, end)} (BR-HH-05, BR-HH-07). {@code start} is
 * inclusive, {@code end} exclusive and equal to the next period's start.
 */
public record BudgetPeriod(LocalDate start, LocalDate end) {

    public BudgetPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Period end must be after its start.");
        }
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && date.isBefore(end);
    }
}
