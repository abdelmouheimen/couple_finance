package com.couplefinance.ai.api;

import java.util.Objects;

/**
 * A schema-valid extraction with its provenance.
 *
 * @param rawOutput the provider's JSON output as received (untrusted; stored 90 days for audit, BR-RCP-18)
 */
public record DocumentExtractionResult(DocumentExtraction extraction, String rawOutput, Provenance provenance,
        long latencyMs) {

    public DocumentExtractionResult {
        Objects.requireNonNull(extraction, "extraction");
        Objects.requireNonNull(rawOutput, "rawOutput");
        Objects.requireNonNull(provenance, "provenance");
    }

    /** Which provider, model and prompt version produced an output (ai.md §9). */
    public record Provenance(String provider, String model, String promptVersion) {
        public Provenance {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(promptVersion, "promptVersion");
        }
    }
}
