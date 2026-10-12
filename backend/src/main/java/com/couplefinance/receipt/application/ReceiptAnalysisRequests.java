package com.couplefinance.receipt.application;

import java.time.Clock;
import java.util.UUID;

import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.identity.api.AiConsentWithdrawn;
import com.couplefinance.identity.api.AiConsents;
import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptAnalysisStore;
import com.couplefinance.receipt.domain.ReceiptErrorCode;
import com.couplefinance.receipt.domain.ReceiptRepository;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit analysis requests (BR-RCP-17): re-running a failed analysis (at most three times) or analysing a receipt
 * uploaded before consent, after the user consented. Also stops queued work when consent is withdrawn (BR-AI-05).
 */
@Service
public class ReceiptAnalysisRequests {

    private static final Logger log = LoggerFactory.getLogger(ReceiptAnalysisRequests.class);

    private final CurrentHousehold currentHousehold;
    private final ReceiptRepository receipts;
    private final ReceiptAnalysisStore store;
    private final AiConsents consents;
    private final TransactionTemplate transaction;
    private final Clock clock;

    ReceiptAnalysisRequests(CurrentHousehold currentHousehold, ReceiptRepository receipts, ReceiptAnalysisStore store,
            AiConsents consents, TransactionTemplate transaction, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.receipts = receipts;
        this.store = store;
        this.consents = consents;
        this.transaction = transaction;
        this.clock = clock;
    }

    public ReceiptView request(UUID id) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable();
        Receipt queued = transaction.execute(s -> {
            Receipt receipt = receipts.lockVisible(id, context.householdId(), context.userId())
                    .orElseThrow(() -> new ApplicationException(ReceiptErrorCode.RECEIPT_NOT_FOUND,
                            "Receipt not found."));
            boolean manualRetry = receipt.requireAnalysable();
            if (!consents.isGranted(context.userId(), AiConsentPurpose.RECEIPT_ANALYSIS)) {
                throw new ApplicationException(ReceiptErrorCode.AI_CONSENT_REQUIRED,
                        "Allow receipt analysis in your privacy settings first.");
            }
            store.request(receipt.id(), context.householdId(), manualRetry ? ReceiptAnalysisStore.Trigger.MANUAL_RETRY
                    : ReceiptAnalysisStore.Trigger.CONSENT_GRANTED, manualRetry, clock.instant());
            return receipts.findVisible(id, context.householdId(), context.userId()).orElseThrow();
        });
        log.info("Receipt analysis requested: receipt={}", id);
        return ReceiptView.from(queued);
    }

    /** BR-AI-05: a withdrawal stops the user's queued analyses immediately (re-reads state; idempotent). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void on(AiConsentWithdrawn event) {
        if (event.purpose() != AiConsentPurpose.RECEIPT_ANALYSIS) {
            return;
        }
        UserId user = new UserId(event.userId());
        if (consents.isGranted(user, AiConsentPurpose.RECEIPT_ANALYSIS)) {
            return; // granted again meanwhile
        }
        int cancelled = store.cancelPendingOf(user, clock.instant());
        log.info("Consent withdrawn: {} queued receipt analyses cancelled", cancelled);
    }
}
