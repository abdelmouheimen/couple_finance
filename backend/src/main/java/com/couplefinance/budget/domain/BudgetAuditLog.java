package com.couplefinance.budget.domain;

import com.couplefinance.shared.id.UserId;

/** Append-only audit trail of the budget module (BR-BUD-07, database-schema.md section 11). */
public interface BudgetAuditLog {

    /** Records the creation of {@code budget} by {@code actor}, in the caller's transaction. */
    void created(Budget budget, UserId actor);

    /**
     * Records the edit of the overall limit, in the caller's transaction, with its old and new values in minor
     * units.
     */
    void overallLimitChanged(Budget budget, Long oldLimitMinor, UserId actor);
}
