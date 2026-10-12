package com.couplefinance.ai.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligenceException.Reason;
import org.jspecify.annotations.Nullable;

/**
 * Provider-independent part of an extraction (ai.md §4.1): versioned prompt, call, strict schema validation and AT MOST
 * ONE repair retry (CLAUDE.md §12.2). Every model call — the repair included — is logged in {@code ai.ai_call} with
 * metadata only (BR-AI-07). Adapters only supply the model call.
 */
final class ExtractionPipeline {

    static final String PROMPT_VERSION = "receipt-extraction-v1";

    /** One call to a model: the prompt (and, on repair, the previous answer) with the page images. */
    @FunctionalInterface
    interface ModelCall {
        ModelAnswer generate(String prompt, List<DocumentExtractionRequest.Page> pages);
    }

    /** A model answer: its text output and token usage when reported. */
    record ModelAnswer(String text, @Nullable Integer tokensIn, @Nullable Integer tokensOut) {
    }

    private final ExtractionSchemaValidator validator;
    private final AiCallLog callLog;
    private final String promptTemplate;
    private final String responseSchema;
    private final Clock clock;

    ExtractionPipeline(ExtractionSchemaValidator validator, AiCallLog callLog, Clock clock) {
        this.validator = validator;
        this.callLog = callLog;
        this.clock = clock;
        this.promptTemplate = resource("/ai/prompts/" + PROMPT_VERSION + ".txt");
        this.responseSchema = resource("/ai/schemas/" + PROMPT_VERSION + ".json");
    }

    /** The JSON response schema sent to providers supporting structured output. */
    String responseSchema() {
        return responseSchema;
    }

    DocumentExtractionResult run(DocumentExtractionRequest request, DocumentExtractionResult.Provenance provenance,
            ModelCall model) {
        long start = clock.millis();
        String prompt = promptTemplate.replace("{{currency}}", request.hints().householdCurrency())
                .replace("{{locales}}", String.join(", ", request.hints().localeTags()));
        Validated first = call(request, provenance, model, prompt);
        if (first.outcome().valid()) {
            return new DocumentExtractionResult(first.outcome().extraction().orElseThrow(), first.answer().text(),
                    provenance, clock.millis() - start);
        }
        String repair = prompt + "\n\nYour previous answer did not match the required JSON schema:\n- "
                + first.outcome().violations().stream().limit(20).collect(Collectors.joining("\n- "))
                + "\nPrevious answer:\n" + truncate(first.answer().text())
                + "\nReturn only the corrected JSON object, transcribed from the images.";
        Validated second = call(request, provenance, model, repair);
        if (!second.outcome().valid()) {
            throw new DocumentIntelligenceException(Reason.SCHEMA_INVALID, provenance, second.answer().text(),
                    clock.millis() - start, "Output does not match the schema after one repair retry", null);
        }
        return new DocumentExtractionResult(second.outcome().extraction().orElseThrow(), second.answer().text(),
                provenance, clock.millis() - start);
    }

    private record Validated(ModelAnswer answer, ExtractionSchemaValidator.Outcome outcome) {
    }

    /** One model call, validated and logged with its real outcome (one {@code ai_call} row per call). */
    private Validated call(DocumentExtractionRequest request, DocumentExtractionResult.Provenance provenance,
            ModelCall model, String prompt) {
        long start = clock.millis();
        ModelAnswer answer;
        try {
            answer = model.generate(prompt, request.pages());
        } catch (DocumentIntelligenceException e) {
            callLog.record(request, provenance, AiCallLog.Outcome.of(e.reason()), clock.millis() - start, null, null);
            throw e;
        }
        ExtractionSchemaValidator.Outcome outcome = validator.validate(answer.text());
        callLog.record(request, provenance, outcome.valid() ? AiCallLog.Outcome.OK : AiCallLog.Outcome.SCHEMA_INVALID,
                clock.millis() - start, answer.tokensIn(), answer.tokensOut());
        return new Validated(answer, outcome);
    }

    private static String truncate(String text) {
        return text.length() <= 20_000 ? text : text.substring(0, 20_000);
    }

    private static String resource(String path) {
        try (InputStream in = ExtractionPipeline.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
