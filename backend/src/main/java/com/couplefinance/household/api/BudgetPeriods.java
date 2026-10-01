package com.couplefinance.household.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the budget period calendar of the caller's household (domain-model.md §4.2). Periods are
 * referenced by {@code (householdId, periodStart)}. Every lookup is scoped by the household of the given context.
 */
public interface BudgetPeriods {

    /** The period whose {@code [start, end)} range contains {@code date}, if the calendar covers it. */
    Optional<BudgetPeriod> findPeriodContaining(HouseholdContext context, LocalDate date);

    /**
     * The periods intersecting the date range {@code [from, to)}, ordered by start.
     *
     * @throws IllegalArgumentException when {@code to} is not after {@code from}
     */
    List<BudgetPeriod> listPeriods(HouseholdContext context, LocalDate from, LocalDate to);
}
