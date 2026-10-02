package com.couplefinance.expense.domain;

import java.time.Instant;

import com.couplefinance.shared.id.UserId;

/** Append-only audit trail of the expense module (BR-EXP-10, database-schema.md section 11). */
public interface ExpenseAuditLog {

    /** Records the creation of {@code expense} by {@code actor}, in the caller's transaction. */
    void created(Expense expense, UserId actor);

    /**
     * Records the edit of an expense, in the caller's transaction: only the fields that differ between
     * {@code before} and the current state of {@code after}, with their old and new values. The entry is visible
     * to the owner only when the expense was PERSONAL before or is after the edit (BR-EXP-10).
     */
    void updated(ExpenseSnapshot before, Expense after, UserId actor);

    /** Records the logical deletion of {@code expense} (BR-EXP-10, BR-EXP-11), in the caller's transaction. */
    void deleted(Expense expense, UserId actor);

    /** Records the restoration of {@code expense} (BR-EXP-10, BR-EXP-11), in the caller's transaction. */
    void restored(Expense expense, UserId actor, Instant deletedAt);
}
