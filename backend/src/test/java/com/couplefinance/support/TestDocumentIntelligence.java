package com.couplefinance.support;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligencePort;

/**
 * The {@link DocumentIntelligencePort} of integration tests: delegates to the configured (fake, consent-guarded)
 * port unless a provider failure is scripted, so the retry and failure paths of the analysis pipeline can be
 * exercised without any real provider (CLAUDE.md §13).
 */
public class TestDocumentIntelligence implements DocumentIntelligencePort {

    private static final DocumentExtractionResult.Provenance PROVENANCE =
            new DocumentExtractionResult.Provenance("fake", "scripted", "receipt-extraction-v1");

    private final DocumentIntelligencePort delegate;
    private final Deque<DocumentIntelligenceException.Reason> failures = new ConcurrentLinkedDeque<>();
    private final AtomicReference<Runnable> beforeNext = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public TestDocumentIntelligence(DocumentIntelligencePort delegate) {
        this.delegate = delegate;
    }

    @Override
    public DocumentExtractionResult extract(DocumentExtractionRequest request) {
        calls.incrementAndGet();
        Runnable hook = beforeNext.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        DocumentIntelligenceException.Reason failure = failures.poll();
        if (failure != null) {
            throw new DocumentIntelligenceException(failure, PROVENANCE,
                    failure == DocumentIntelligenceException.Reason.SCHEMA_INVALID ? "{\"oops\":true}" : null, 5,
                    "scripted " + failure, null);
        }
        return delegate.extract(request);
    }

    /** The next calls fail with these reasons, in order; later calls succeed again. */
    public void failNext(DocumentIntelligenceException.Reason... reasons) {
        failures.addAll(java.util.List.of(reasons));
    }

    /** Runs once, during the next call: simulates something happening while the provider is working. */
    public void duringNextCall(Runnable action) {
        beforeNext.set(action);
    }

    /** Number of extraction calls received so far. */
    public int calls() {
        return calls.get();
    }

    public void reset() {
        failures.clear();
        beforeNext.set(null);
    }
}
