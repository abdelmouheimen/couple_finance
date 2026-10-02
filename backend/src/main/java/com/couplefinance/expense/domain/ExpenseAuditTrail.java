package com.couplefinance.expense.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Read side of the expense audit trail (BR-EXP-10), always scoped by household and visibility (BR-EXP-07). */
public interface ExpenseAuditTrail {

    /**
     * The audit entries of one expense, oldest first. Rows of a PERSONAL expense are returned only to their owner:
     * the filter {@code owner_user_id IS NULL OR owner_user_id = userId} is part of the query.
     */
    List<Entry> history(UUID expenseId, UUID householdId, UUID userId);

    /** One audit entry; {@code changes} maps a field to its {@code old} and {@code new} values. */
    record Entry(UUID id, String action, String actorType, @Nullable UUID actorUserId, @Nullable String actorSystem,
            Instant occurredAt, Map<String, Object> changes) {}
}
