package com.couplefinance.receipt.domain;

/** AI processing state of a receipt, independent of its evidence lifecycle (database-schema.md section 8.1). */
public enum AnalysisStatus {
    /** Never sent to an AI provider: the uploader had not consented to receipt analysis (BR-RCP-17). */
    SKIPPED_NO_CONSENT,
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
