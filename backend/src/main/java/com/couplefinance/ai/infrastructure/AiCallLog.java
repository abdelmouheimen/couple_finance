package com.couplefinance.ai.infrastructure;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import org.jspecify.annotations.Nullable;

/** Metadata-only log of every AI call (BR-AI-07, database-schema.md section 12.2): never prompts or outputs. */
interface AiCallLog {

    enum Outcome {
        OK, SCHEMA_INVALID, TIMEOUT, ERROR, REFUSED, CONSENT_MISSING;

        static Outcome of(DocumentIntelligenceException.Reason reason) {
            return switch (reason) {
                case TIMEOUT -> TIMEOUT;
                case PROVIDER_ERROR, RATE_LIMITED -> ERROR;
                case SCHEMA_INVALID -> SCHEMA_INVALID;
                case REFUSED -> REFUSED;
                case CONSENT_MISSING -> CONSENT_MISSING;
            };
        }
    }

    void record(DocumentExtractionRequest request, DocumentExtractionResult.Provenance provenance, Outcome outcome,
            long latencyMs, @Nullable Integer tokensIn, @Nullable Integer tokensOut);
}
