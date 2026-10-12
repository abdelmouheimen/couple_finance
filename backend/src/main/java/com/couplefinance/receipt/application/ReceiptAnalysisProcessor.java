package com.couplefinance.receipt.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.couplefinance.ai.api.DocumentExtraction;
import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligencePort;
import com.couplefinance.categorization.api.MerchantNames;
import com.couplefinance.categorization.api.NormalisedMerchant;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.identity.api.AiConsents;
import com.couplefinance.receipt.domain.ReceiptAnalysisStore;
import com.couplefinance.receipt.domain.ReceiptAnalysisStore.Claim;
import com.couplefinance.receipt.domain.ReceiptDraft;
import com.couplefinance.receipt.domain.ReceiptDraftBuilder;
import com.couplefinance.receipt.domain.ReceiptTranscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes one claimed analysis job (architecture.md section 6.2, ai.md section 4.1): consent re-check → read the
 * stored pages → provider call through the AI port → deterministic draft → conditional write. No database
 * transaction is open during storage reads or the provider call (CLAUDE.md section 6.3). Transient failures are
 * retried by re-queueing with exponential backoff (at most 3 automatic attempts); other failures end the analysis
 * with an explicit reason (BR-RCP-17). Re-running a job is harmless: every write is conditional.
 */
@Service
public class ReceiptAnalysisProcessor {

    static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(ReceiptAnalysisProcessor.class);

    private final ReceiptAnalysisStore store;
    private final DocumentStorage storage;
    private final DocumentIntelligencePort intelligence;
    private final AiConsents consents;
    private final HouseholdLedgerRules ledgers;
    private final ReceiptAnalysisProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    ReceiptAnalysisProcessor(ReceiptAnalysisStore store, DocumentStorage storage, DocumentIntelligencePort intelligence,
            AiConsents consents, HouseholdLedgerRules ledgers, ReceiptAnalysisProperties properties,
            TransactionTemplate transaction, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.intelligence = intelligence;
        this.consents = consents;
        this.ledgers = ledgers;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    public void process(Claim claim) {
        if (!consents.isGranted(claim.uploadedBy(), AiConsentPurpose.RECEIPT_ANALYSIS)) {
            transaction.executeWithoutResult(s -> store.cancel(claim, clock.instant()));
            log.info("Receipt analysis cancelled (no consent): receipt={}", claim.receiptId());
            return;
        }
        LedgerProfile profile = ledgers.profile(claim.householdId());
        List<DocumentExtractionRequest.Page> pages = new ArrayList<>();
        try {
            for (ReceiptAnalysisStore.PageRef page : claim.pages()) {
                pages.add(new DocumentExtractionRequest.Page(storage.get(page.storageKey()), page.contentType()));
            }
        } catch (RuntimeException e) {
            failure(claim, null, "STORAGE_UNAVAILABLE", true, "PROVIDER_ERROR", false);
            log.warn("Receipt analysis postponed (storage): receipt={} ({})", claim.receiptId(),
                    e.getClass().getSimpleName());
            return;
        }
        DocumentExtractionResult result;
        try {
            result = intelligence.extract(new DocumentExtractionRequest(claim.uploadedBy().value(),
                    claim.householdId().value(), pages,
                    new DocumentExtractionRequest.Hints(profile.currency().value(), List.of()),
                    claim.jobId().toString()));
        } catch (DocumentIntelligenceException e) {
            handle(claim, e);
            return;
        } catch (RuntimeException e) {
            failure(claim, null, "PROVIDER_ERROR", true, "PROVIDER_ERROR", false);
            log.warn("Receipt analysis failed unexpectedly: receipt={} ({})", claim.receiptId(),
                    e.getClass().getSimpleName());
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(profile.timezone()));
        ReceiptDraft draft = ReceiptDraftBuilder.build(transcription(result.extraction()), profile.currency(),
                profile.maxExpense(), today, ReceiptAnalysisProcessor::merchantKey);
        ReceiptAnalysisStore.Attempt attempt = new ReceiptAnalysisStore.Attempt(result.provenance().provider(),
                result.provenance().model(), result.provenance().promptVersion(), "SUCCEEDED", result.rawOutput(),
                result.latencyMs());
        Boolean applied = transaction.execute(s -> store.succeed(claim, attempt, draft, clock.instant()));
        log.info("Receipt analysed: receipt={} applied={}", claim.receiptId(), applied);
    }

    private void handle(Claim claim, DocumentIntelligenceException e) {
        if (e.reason() == DocumentIntelligenceException.Reason.CONSENT_MISSING) {
            transaction.executeWithoutResult(s -> store.cancel(claim, clock.instant()));
            return;
        }
        String outcome = switch (e.reason()) {
            case TIMEOUT -> "TIMEOUT";
            case SCHEMA_INVALID -> "SCHEMA_INVALID";
            case REFUSED -> "REFUSED";
            default -> "PROVIDER_ERROR";
        };
        ReceiptAnalysisStore.Attempt attempt = e.provenance() == null ? null
                : new ReceiptAnalysisStore.Attempt(e.provenance().provider(), e.provenance().model(),
                        e.provenance().promptVersion(), outcome, e.rawOutput(), e.latencyMs());
        String failureReason = switch (e.reason()) {
            case TIMEOUT -> "TIMEOUT";
            case SCHEMA_INVALID -> "SCHEMA_INVALID";
            case RATE_LIMITED -> "QUOTA_EXCEEDED";
            default -> "PROVIDER_ERROR";
        };
        failure(claim, attempt, e.reason().name(), e.reason().retryable(), failureReason, true);
        log.info("Receipt analysis attempt failed: receipt={} reason={} attempt={}", claim.receiptId(), e.reason(),
                claim.attemptCount() + 1);
    }

    private void failure(Claim claim, ReceiptAnalysisStore.Attempt attempt, String errorCode, boolean retryable,
            String failureReason, boolean processed) {
        Instant now = clock.instant();
        boolean retry = retryable && claim.attemptCount() + 1 < MAX_ATTEMPTS;
        Instant retryAt = retry ? now.plus(properties.retryBackoff().multipliedBy(1L << claim.attemptCount())) : null;
        transaction.executeWithoutResult(s -> store.fail(claim, attempt, errorCode, retryAt, failureReason,
                processed, now));
    }

    private static Optional<String> merchantKey(String raw) {
        NormalisedMerchant normalised = MerchantNames.normalise(raw);
        return normalised.isEmpty() ? Optional.empty() : Optional.of(normalised.key());
    }

    static ReceiptTranscription transcription(DocumentExtraction e) {
        return new ReceiptTranscription(e.documentType(), e.merchantName(), e.merchantCountry(), e.language(),
                e.purchaseDate(), e.observedFormats().decimalSeparator(), e.observedFormats().dateOrder(),
                e.currencySymbolOrCode(), e.taxMode(), e.total(), e.handwrittenTotal(), e.subtotal(),
                e.taxLines().stream().map(t -> new ReceiptTranscription.Tax(t.rate(), t.amount())).toList(),
                e.lineItems().stream().map(l -> new ReceiptTranscription.Line(l.type(), l.description(), l.quantity(),
                        l.amount())).toList(),
                e.payments().stream().map(DocumentExtraction.Payment::amount).toList(), e.change(),
                e.confidence().merchantName(), e.confidence().purchaseDate(), e.confidence().total(),
                e.confidence().currency());
    }
}
