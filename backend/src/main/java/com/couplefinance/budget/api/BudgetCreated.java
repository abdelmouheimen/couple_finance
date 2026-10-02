package com.couplefinance.budget.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A budget was created for a period (domain-model.md section 8). Published in the creating transaction through the
 * transactional event publication registry. It carries no amount: consumers re-read the current state.
 */
public record BudgetCreated(UUID budgetId, UUID householdId, LocalDate periodStart, Instant occurredAt) {

    public BudgetCreated {
        Objects.requireNonNull(budgetId, "budgetId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
