package com.couplefinance.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligenceException.Reason;
import com.couplefinance.ai.api.DocumentIntelligencePort;
import com.couplefinance.identity.api.AiConsentPurpose;
import org.junit.jupiter.api.Test;

/** BR-AI-05 (ADR-005 §8): consent is re-asserted just before the provider call; without it nothing is sent. */
class ConsentGuardedDocumentIntelligenceTest {

    private final RecordingAiCallLog callLog = new RecordingAiCallLog();

    @Test
    void BR_AI_05_without_consent_the_provider_is_never_called() {
        AtomicInteger providerCalls = new AtomicInteger();
        DocumentIntelligencePort guarded = new ConsentGuardedDocumentIntelligence(request -> {
            providerCalls.incrementAndGet();
            throw new AssertionError("must not be called");
        }, "gemini", (user, purpose) -> false, callLog, 2);

        assertThatThrownBy(() -> guarded.extract(DocumentIntelligencePortContract.request()))
                .isInstanceOfSatisfying(DocumentIntelligenceException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.CONSENT_MISSING);
                    assertThat(e.reason().retryable()).isFalse();
                });
        assertThat(providerCalls).hasValue(0);
        assertThat(callLog.outcomes()).containsExactly(AiCallLog.Outcome.CONSENT_MISSING);
    }

    @Test
    void BR_AI_05_only_the_receipt_analysis_consent_of_the_uploader_authorises_the_call() {
        DocumentExtractionRequest request = DocumentIntelligencePortContract.request();
        DocumentExtractionResult expected = new FakeDocumentIntelligenceAdapter(new ExtractionPipeline(
                new ExtractionSchemaValidator(tools.jackson.databind.json.JsonMapper.builder().build()), callLog,
                java.time.Clock.systemUTC())).extract(request);
        DocumentIntelligencePort guarded = new ConsentGuardedDocumentIntelligence(r -> expected, "fake",
                (user, purpose) -> user.value().equals(request.consentingUser())
                        && purpose == AiConsentPurpose.RECEIPT_ANALYSIS, callLog, 2);

        assertThat(guarded.extract(request)).isSameAs(expected);
    }

    @Test
    void concurrent_calls_are_bounded() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        DocumentIntelligencePort guarded = new ConsentGuardedDocumentIntelligence(request -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            throw new DocumentIntelligenceException(Reason.PROVIDER_ERROR, null, null, 0, "done", null);
        }, "fake", (user, purpose) -> true, callLog, 2);

        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) {
                pool.submit(() -> {
                    try {
                        guarded.extract(DocumentIntelligencePortContract.request());
                    } catch (DocumentIntelligenceException expected) {
                        // each call ends with the scripted failure
                    }
                });
            }
            Thread.sleep(300);
            assertThat(running).hasValue(2);
            release.countDown();
        }
        assertThat(peak).hasValue(2);
    }
}
