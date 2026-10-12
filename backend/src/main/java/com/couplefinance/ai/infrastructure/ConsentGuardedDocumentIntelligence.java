package com.couplefinance.ai.infrastructure;

import java.util.concurrent.Semaphore;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligenceException.Reason;
import com.couplefinance.ai.api.DocumentIntelligencePort;
import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.identity.api.AiConsents;
import com.couplefinance.shared.id.UserId;

/**
 * The {@link DocumentIntelligencePort} seen by business modules: re-asserts the uploader's receipt-analysis consent
 * just before the provider call (ADR-005 §8, defence in depth) and bounds the number of concurrent outbound calls
 * (ai.md §8), then delegates to the configured provider adapter.
 */
final class ConsentGuardedDocumentIntelligence implements DocumentIntelligencePort {

    private final DocumentIntelligencePort provider;
    private final String providerName;
    private final AiConsents consents;
    private final AiCallLog callLog;
    private final Semaphore permits;

    ConsentGuardedDocumentIntelligence(DocumentIntelligencePort provider, String providerName, AiConsents consents,
            AiCallLog callLog, int maxConcurrency) {
        this.provider = provider;
        this.providerName = providerName;
        this.consents = consents;
        this.callLog = callLog;
        this.permits = new Semaphore(Math.max(1, maxConcurrency), true);
    }

    @Override
    public DocumentExtractionResult extract(DocumentExtractionRequest request) {
        if (!consents.isGranted(new UserId(request.consentingUser()), AiConsentPurpose.RECEIPT_ANALYSIS)) {
            DocumentExtractionResult.Provenance provenance = new DocumentExtractionResult.Provenance(providerName,
                    "-", ExtractionPipeline.PROMPT_VERSION);
            callLog.record(request, provenance, AiCallLog.Outcome.CONSENT_MISSING, 0, null, null);
            throw new DocumentIntelligenceException(Reason.CONSENT_MISSING, provenance, null, 0,
                    "No receipt-analysis consent: nothing was sent", null);
        }
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentIntelligenceException(Reason.PROVIDER_ERROR, null, null, 0, "Interrupted", e);
        }
        try {
            return provider.extract(request);
        } finally {
            permits.release();
        }
    }
}
