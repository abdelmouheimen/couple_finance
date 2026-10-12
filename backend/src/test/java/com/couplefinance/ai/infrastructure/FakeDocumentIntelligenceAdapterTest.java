package com.couplefinance.ai.infrastructure;

import java.util.concurrent.atomic.AtomicInteger;

import com.couplefinance.ai.api.DocumentIntelligencePort;

/** The fake adapter honours the same contract as a real provider (provider switching, ADR-008). */
class FakeDocumentIntelligenceAdapterTest extends DocumentIntelligencePortContract {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    DocumentIntelligencePort adapterAnswering(String... outputs) {
        return new FakeDocumentIntelligenceAdapter(pipeline, () -> outputs[calls.getAndIncrement()]);
    }

    @Override
    int providerCalls() {
        return calls.get();
    }

    @Override
    String providerName() {
        return "fake";
    }
}
