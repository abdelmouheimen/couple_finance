package com.couplefinance.receipt.domain;

import com.couplefinance.shared.id.UserId;

/**
 * Outbound port writing {@code receipt.audit_event} rows in the caller's transaction (database-schema.md section 11).
 * Audit rows describe the receipt (kind, page count, sizes, hashes), never its content.
 */
public interface ReceiptAuditLog {

    void uploaded(Receipt receipt, UserId actor);

    void deleted(Receipt receipt, UserId actor);
}
