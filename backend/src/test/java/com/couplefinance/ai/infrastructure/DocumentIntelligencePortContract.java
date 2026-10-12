package com.couplefinance.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligenceException.Reason;
import com.couplefinance.ai.api.DocumentIntelligencePort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract of every {@link DocumentIntelligencePort} adapter (ADR-008): the same behaviour whichever provider is
 * configured, so switching provider changes nothing for the receipt module. Each adapter test subclasses this and
 * says how to make its provider answer the given model outputs, one per call.
 */
abstract class DocumentIntelligencePortContract {

    static final String INVALID = "{\"documentType\":\"RECEIPT\",\"total\":13.78,\"surprise\":true}";

    final JsonMapper json = JsonMapper.builder().build();
    final RecordingAiCallLog callLog = new RecordingAiCallLog();
    final ExtractionPipeline pipeline = new ExtractionPipeline(new ExtractionSchemaValidator(json), callLog,
            Clock.systemUTC());

    /** An adapter whose model answers {@code outputs} in order, one per call. */
    abstract DocumentIntelligencePort adapterAnswering(String... outputs);

    /** Number of calls the provider actually received. */
    abstract int providerCalls();

    abstract String providerName();

    static DocumentExtractionRequest request() {
        return new DocumentExtractionRequest(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new DocumentExtractionRequest.Page(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3},
                        "image/jpeg")),
                new DocumentExtractionRequest.Hints("EUR", List.of("fr-FR")), "job-1");
    }

    @Test
    void a_valid_answer_is_returned_as_printed_with_its_provenance() {
        DocumentExtractionResult result = adapterAnswering(FakeDocumentIntelligenceAdapter.FIXTURE)
                .extract(request());

        assertThat(result.extraction().merchantName()).contains("Carrefour");
        assertThat(result.extraction().total()).contains("13,78");
        assertThat(result.extraction().purchaseDate()).contains("04/10/2026");
        assertThat(result.extraction().currencySymbolOrCode()).contains("€");
        assertThat(result.extraction().lineItems()).hasSize(4);
        assertThat(result.extraction().payments()).singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualTo("13,78"));
        assertThat(result.rawOutput()).contains("13,78");
        assertThat(result.provenance().provider()).isEqualTo(providerName());
        assertThat(result.provenance().promptVersion()).isEqualTo(ExtractionPipeline.PROMPT_VERSION);
        assertThat(providerCalls()).isEqualTo(1);
        assertThat(callLog.outcomes()).containsExactly(AiCallLog.Outcome.OK);
    }

    @Test
    void BR_RCP_03_an_invalid_answer_gets_exactly_one_repair_retry() {
        DocumentExtractionResult result = adapterAnswering(INVALID, FakeDocumentIntelligenceAdapter.FIXTURE)
                .extract(request());

        assertThat(result.extraction().total()).contains("13,78");
        assertThat(providerCalls()).isEqualTo(2);
        assertThat(callLog.outcomes()).containsExactly(AiCallLog.Outcome.SCHEMA_INVALID, AiCallLog.Outcome.OK);
    }

    @Test
    void BR_RCP_03_an_answer_still_invalid_after_the_repair_is_a_failure_never_parsed() {
        DocumentIntelligencePort adapter = adapterAnswering(INVALID, "not json at all",
                FakeDocumentIntelligenceAdapter.FIXTURE);

        assertThatThrownBy(() -> adapter.extract(request()))
                .isInstanceOfSatisfying(DocumentIntelligenceException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.SCHEMA_INVALID);
                    assertThat(e.reason().retryable()).isFalse();
                    assertThat(e.rawOutput()).isEqualTo("not json at all");
                    assertThat(e.provenance()).isNotNull();
                });
        assertThat(providerCalls()).isEqualTo(2);
        assertThat(callLog.outcomes()).containsExactly(AiCallLog.Outcome.SCHEMA_INVALID,
                AiCallLog.Outcome.SCHEMA_INVALID);
    }
}
