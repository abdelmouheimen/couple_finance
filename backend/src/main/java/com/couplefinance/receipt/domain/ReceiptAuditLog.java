package com.couplefinance.receipt.domain;

import com.couplefinance.shared.id.UserId;

/**
 * Outbound port writing {@code receipt.audit_event} rows in the caller's transaction (database-schema.md section 11).
 * Audit rows describe the receipt (kind, page count, sizes, hashes), never its content.
 */
public interface ReceiptAuditLog {

    void uploaded(Receipt receipt, UserId actor);

    void deleted(Receipt receipt, UserId actor);

    /**
     * Records that the receipt became evidence of an expense.
     *
     * @param action {@code CONFIRM} (new expense) or {@code ATTACH} (existing expense)
     */
    void linked(Receipt receipt, UserId actor, String action);
}
