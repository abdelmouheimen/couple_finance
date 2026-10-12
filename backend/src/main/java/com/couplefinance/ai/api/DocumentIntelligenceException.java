package com.couplefinance.ai.api;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** A failed extraction, with a stable reason; never carries document content. */
public final class DocumentIntelligenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** No answer within the timeout. Retryable. */
        TIMEOUT(true),
        /** Provider-side or transport error (5xx, connection). Retryable. */
        PROVIDER_ERROR(true),
        /** Quota or rate limit (429). Retryable with backoff. */
        RATE_LIMITED(true),
        /** Output did not match the schema, even after the single repair retry (BR-RCP-03). */
        SCHEMA_INVALID(false),
        /** The provider refused (safety block, empty answer). */
        REFUSED(false),
        /** The uploader has no active receipt-analysis consent (BR-AI-05): nothing was sent. */
        CONSENT_MISSING(false);

        private final boolean retryable;

        Reason(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Reason reason;
    private final DocumentExtractionResult.@Nullable Provenance provenance;
    private final @Nullable String rawOutput;
    private final long latencyMs;

    public DocumentIntelligenceException(Reason reason, DocumentExtractionResult.@Nullable Provenance provenance,
            @Nullable String rawOutput, long latencyMs, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.provenance = provenance;
        this.rawOutput = rawOutput;
        this.latencyMs = latencyMs;
    }

    public Reason reason() {
        return reason;
    }

    public DocumentExtractionResult.@Nullable Provenance provenance() {
        return provenance;
    }

    /** The last (invalid) output, for audit of schema failures; {@code null} when nothing usable came back. */
    public @Nullable String rawOutput() {
        return rawOutput;
    }

    public long latencyMs() {
        return latencyMs;
    }
}
