package com.couplefinance.budget.api;

import java.time.LocalDate;
import java.util.Optional;

import com.couplefinance.household.api.HouseholdContext;

/**
 * Read access to the budget consumption of a period for other modules (analytics). The household comes from the
 * caller's context; the figures are computed on read and never stored.
 */
public interface BudgetSummaries {

    /**
     * The overall-limit summary of the household's budget for the period starting on {@code periodStart}; empty
     * when the period has no budget or the budget has no overall limit.
     */
    Optional<BudgetSummary> overallSummary(HouseholdContext context, LocalDate periodStart);
}
