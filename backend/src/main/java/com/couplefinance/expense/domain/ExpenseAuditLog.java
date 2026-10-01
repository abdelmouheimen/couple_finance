package com.couplefinance.expense.domain;

import com.couplefinance.shared.id.UserId;

/** Append-only audit trail of the expense module (BR-EXP-10, database-schema.md section 11). */
public interface ExpenseAuditLog {

    /** Records the creation of {@code expense} by {@code actor}, in the caller's transaction. */
    void created(Expense expense, UserId actor);
}
