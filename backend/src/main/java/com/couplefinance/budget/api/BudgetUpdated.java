package com.couplefinance.budget.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The limits of a budget were changed (domain-model.md section 8). Published in the editing transaction through
 * the transactional event publication registry. It carries no amount: consumers re-read the current state.
 */
public record BudgetUpdated(UUID budgetId, UUID householdId, LocalDate periodStart, Instant occurredAt) {

    public BudgetUpdated {
        Objects.requireNonNull(budgetId, "budgetId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
