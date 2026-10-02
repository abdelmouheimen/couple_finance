package com.couplefinance.expense.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An expense was edited (domain-model.md section 7.5). Published in the editing transaction through the
 * transactional event publication registry. Like {@link ExpenseCreated} it carries no amount, merchant or note:
 * consumers re-read the current state. It carries the visibility before and after the edit so that consumers can
 * tell a SHARED to PERSONAL switch (the expense leaves household budgets) from an ordinary edit.
 *
 * @param ownerBefore {@code null} when the expense was SHARED, else its owner
 * @param ownerAfter  {@code null} when the expense is SHARED after the edit, else its owner
 */
public record ExpenseUpdated(UUID expenseId, UUID householdId, @Nullable UUID ownerBefore,
        @Nullable UUID ownerAfter, Instant occurredAt) {

    public ExpenseUpdated {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
