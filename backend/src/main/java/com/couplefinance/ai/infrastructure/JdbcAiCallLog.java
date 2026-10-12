package com.couplefinance.ai.infrastructure;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.shared.id.UuidV7;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes {@code ai.ai_call} rows (auto-commit, outside any business transaction): metadata only. */
@Repository
class JdbcAiCallLog implements AiCallLog {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcAiCallLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void record(DocumentExtractionRequest request, DocumentExtractionResult.Provenance provenance,
            Outcome outcome, long latencyMs, @Nullable Integer tokensIn, @Nullable Integer tokensOut) {
        jdbc.sql("""
                        INSERT INTO ai.ai_call
                            (id, created_at, use_case, provider, model, prompt_version, household_id, user_id,
                             correlation_id, latency_ms, tokens_in, tokens_out, outcome)
                        VALUES (:id, :createdAt, 'RECEIPT_EXTRACTION', :provider, :model, :promptVersion, :household,
                                :user, :correlation, :latency, :tokensIn, :tokensOut, :outcome)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("createdAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("provider", provenance.provider())
                .param("model", provenance.model())
                .param("promptVersion", provenance.promptVersion())
                .param("household", request.householdId())
                .param("user", request.consentingUser())
                .param("correlation", request.correlationId())
                .param("latency", (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs)))
                .param("tokensIn", tokensIn)
                .param("tokensOut", tokensOut)
                .param("outcome", outcome.name())
                .update();
    }
}
