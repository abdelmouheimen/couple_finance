package com.couplefinance.ai.api;

/**
 * Outbound port of document intelligence (ADR-008, ai.md §4): transcribes a receipt as printed into the extraction
 * schema of ai.md §4.2. The result is untrusted input (BR-AI-03): it has passed schema validation only; the caller
 * parses and validates every value deterministically (ADR-004) and never treats it as confirmed data.
 *
 * <p>Implementations perform network I/O: never call them inside a database transaction. They re-check the
 * consenting user's receipt-analysis consent just before the provider call (ADR-005 §8).
 */
public interface DocumentIntelligencePort {

    /**
     * @throws DocumentIntelligenceException on any failure, with its reason (retryable or not) and provenance
     */
    DocumentExtractionResult extract(DocumentExtractionRequest request);
}
