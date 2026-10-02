package com.couplefinance.budget.domain;

import java.util.Map;
import java.util.UUID;

import com.couplefinance.shared.id.UserId;

/** Append-only audit trail of the budget module (BR-BUD-07, database-schema.md section 11). */
public interface BudgetAuditLog {

    /**
     * Records the creation of {@code budget} by {@code actor}, in the caller's transaction, with its initial
     * category limits (category id to minor units; empty when none).
     */
    void created(Budget budget, Map<UUID, Long> categoryLimitsMinor, UserId actor);

    /**
     * Records the edit of the overall limit, in the caller's transaction, with its old and new values in minor
     * units.
     */
    void overallLimitChanged(Budget budget, Long oldLimitMinor, UserId actor);

    /**
     * Records the edit of the category limits (BR-BUD-07), in the caller's transaction: the old and new lines as
     * category id to minor units.
     */
    void categoryLimitsChanged(Budget budget, Map<UUID, Long> oldMinor, Map<UUID, Long> newMinor, UserId actor);
}
