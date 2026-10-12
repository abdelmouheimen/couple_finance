package com.couplefinance.ai.infrastructure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import org.jspecify.annotations.Nullable;

/** In-memory {@link AiCallLog} for adapter tests. */
final class RecordingAiCallLog implements AiCallLog {

    record Call(String provider, String model, String promptVersion, Outcome outcome, @Nullable Integer tokensIn,
            @Nullable Integer tokensOut) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();

    @Override
    public void record(DocumentExtractionRequest request, DocumentExtractionResult.Provenance provenance,
            Outcome outcome, long latencyMs, @Nullable Integer tokensIn, @Nullable Integer tokensOut) {
        calls.add(new Call(provenance.provider(), provenance.model(), provenance.promptVersion(), outcome, tokensIn,
                tokensOut));
    }

    List<Outcome> outcomes() {
        return calls.stream().map(Call::outcome).toList();
    }
}
