package com.couplefinance.receipt.domain;

/** Evidence lifecycle of a receipt (database-schema.md section 8.1). */
public enum ReceiptStatus {
    /** Not yet linked; the draft is editable by its uploader and visible to them only (BR-RCP-16). */
    OPEN,
    /** Converted into a new expense, exactly once (BR-RCP-15). */
    CONFIRMED,
    /** Linked to an existing expense. */
    ATTACHED,
    /** Open for more than 30 days; images scheduled for purge (BR-RCP-19). */
    ABANDONED
}
