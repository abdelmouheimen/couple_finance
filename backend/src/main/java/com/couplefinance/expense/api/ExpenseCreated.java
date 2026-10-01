package com.couplefinance.expense.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An expense was created (domain-model.md section 7). Published in the creating transaction through the
 * transactional event publication registry; consumers (budget, categorization...) re-read the current state
 * instead of trusting this payload, which deliberately carries no amount, merchant or note.
 *
 * @param ownerUserId {@code null} for a SHARED expense, the owner for a PERSONAL one: listeners must apply the
 *                    visibility rules of BR-EXP-07 and never relay a personal expense to the partner
 */
public record ExpenseCreated(UUID expenseId, UUID householdId, @Nullable UUID ownerUserId, Instant occurredAt) {

    public ExpenseCreated {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
