package com.couplefinance.receipt.domain;

/**
 * The combined lifecycle state exposed by the API (domain-model.md; database-schema.md section 8.1 maps it from
 * {@link ReceiptStatus} and {@link AnalysisStatus}).
 */
public enum ReceiptState {
    UPLOADED,
    QUEUED,
    ANALYSING,
    READY_FOR_REVIEW,
    FAILED,
    CONFIRMED,
    ATTACHED,
    ABANDONED;

    public static ReceiptState of(ReceiptStatus status, AnalysisStatus analysis) {
        return switch (status) {
            case CONFIRMED -> CONFIRMED;
            case ATTACHED -> ATTACHED;
            case ABANDONED -> ABANDONED;
            case OPEN -> switch (analysis) {
                case SKIPPED_NO_CONSENT, CANCELLED -> UPLOADED;
                case PENDING -> QUEUED;
                case RUNNING -> ANALYSING;
                case SUCCEEDED -> READY_FOR_REVIEW;
                case FAILED -> FAILED;
            };
        };
    }
}
