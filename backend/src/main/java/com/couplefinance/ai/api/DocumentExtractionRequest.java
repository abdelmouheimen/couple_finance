package com.couplefinance.ai.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What is sent for one extraction: only the page images and minimal hints (BR-AI-06). The user and household ids
 * stay on our side (consent check, call log); they are never transmitted to the provider.
 *
 * @param consentingUser the uploader whose receipt-analysis consent authorises the call
 * @param correlationId  pseudonymous id for the call log (e.g. the job id)
 */
public record DocumentExtractionRequest(UUID consentingUser, UUID householdId, List<Page> pages, Hints hints,
        String correlationId) {

    public DocumentExtractionRequest {
        Objects.requireNonNull(consentingUser, "consentingUser");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(hints, "hints");
        Objects.requireNonNull(correlationId, "correlationId");
        pages = List.copyOf(pages);
        if (pages.isEmpty() || pages.size() > 5) {
            throw new IllegalArgumentException("1 to 5 pages");
        }
    }

    /** A re-encoded page image (never the original upload). */
    public record Page(byte[] content, String mimeType) {
        public Page {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(mimeType, "mimeType");
        }
    }

    /**
     * @param householdCurrency ISO 4217 code, a hint only (the model transcribes the printed symbol)
     * @param localeTags        BCP 47 tags of the household, hints only
     */
    public record Hints(String householdCurrency, List<String> localeTags) {
        public Hints {
            Objects.requireNonNull(householdCurrency, "householdCurrency");
            localeTags = List.copyOf(localeTags);
        }
    }
}
