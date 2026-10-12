package com.couplefinance.ai.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligenceException.Reason;
import com.couplefinance.ai.api.DocumentIntelligencePort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini adapter of {@link DocumentIntelligencePort} (ADR-008) over the REST {@code generateContent} API — plain HTTP,
 * no vendor SDK. Structured output: {@code responseMimeType=application/json} with the extraction response schema,
 * temperature 0. Two endpoint modes: Vertex AI in an EU region (production, OAuth access token) and the Gemini
 * Developer API (development, paid key). The privacy gate is enforced when the bean is created
 * ({@link AiConfiguration}). Nothing but the prompt, the page images and the hints is sent.
 */
final class GeminiDocumentIntelligenceAdapter implements DocumentIntelligencePort {

    static final String PROVIDER = "gemini";
    private static final Set<String> REFUSALS = Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII",
            "RECITATION", "IMAGE_SAFETY");

    private final ExtractionPipeline pipeline;
    private final HttpClient http;
    private final JsonMapper json;
    private final URI endpoint;
    private final Supplier<String> authorization;
    private final boolean apiKeyMode;
    private final Duration timeout;
    private final String model;

    /**
     * @param authorization supplies the API key (api-key mode) or an OAuth access token (vertex mode) per call
     */
    GeminiDocumentIntelligenceAdapter(ExtractionPipeline pipeline, HttpClient http, JsonMapper json, URI endpoint,
            boolean apiKeyMode, Supplier<String> authorization, Duration timeout, String model) {
        this.pipeline = pipeline;
        this.http = http;
        this.json = json;
        this.endpoint = endpoint;
        this.apiKeyMode = apiKeyMode;
        this.authorization = authorization;
        this.timeout = timeout;
        this.model = model;
    }

    @Override
    public DocumentExtractionResult extract(DocumentExtractionRequest request) {
        DocumentExtractionResult.Provenance provenance = new DocumentExtractionResult.Provenance(PROVIDER, model,
                ExtractionPipeline.PROMPT_VERSION);
        return pipeline.run(request, provenance, (prompt, pages) -> generate(prompt, pages, provenance));
    }

    private ExtractionPipeline.ModelAnswer generate(String prompt, List<DocumentExtractionRequest.Page> pages,
            DocumentExtractionResult.Provenance provenance) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(prompt, pages), StandardCharsets.UTF_8));
        String credential;
        try {
            credential = authorization.get();
        } catch (RuntimeException e) {
            throw failure(Reason.PROVIDER_ERROR, provenance, "Gemini credentials unavailable", e);
        }
        if (apiKeyMode) {
            request.header("x-goog-api-key", credential);
        } else {
            request.header("Authorization", "Bearer " + credential);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw failure(Reason.TIMEOUT, provenance, "Gemini timed out", e);
        } catch (IOException e) {
            throw failure(Reason.PROVIDER_ERROR, provenance, "Gemini unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(Reason.PROVIDER_ERROR, provenance, "Interrupted", e);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw failure(Reason.RATE_LIMITED, provenance, "Gemini rate limit", null);
        }
        if (status == 408 || status == 504) {
            throw failure(Reason.TIMEOUT, provenance, "Gemini timed out (" + status + ")", null);
        }
        if (status != 200) {
            throw failure(Reason.PROVIDER_ERROR, provenance, "Gemini answered " + status, null);
        }
        return answer(response.body(), provenance);
    }

    private ExtractionPipeline.ModelAnswer answer(String body, DocumentExtractionResult.Provenance provenance) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            throw failure(Reason.PROVIDER_ERROR, provenance, "Gemini answer is not JSON", e);
        }
        if (root.path("promptFeedback").hasNonNull("blockReason")) {
            throw failure(Reason.REFUSED, provenance, "Gemini blocked the prompt", null);
        }
        JsonNode candidate = root.path("candidates").path(0);
        if (REFUSALS.contains(candidate.path("finishReason").asString(""))) {
            throw failure(Reason.REFUSED, provenance, "Gemini refused: " + candidate.path("finishReason").asString(),
                    null);
        }
        StringBuilder text = new StringBuilder();
        candidate.path("content").path("parts").forEach(part -> {
            if (part.hasNonNull("text")) {
                text.append(part.get("text").asString());
            }
        });
        if (text.isEmpty()) {
            throw failure(Reason.REFUSED, provenance, "Gemini returned no content", null);
        }
        JsonNode usage = root.path("usageMetadata");
        return new ExtractionPipeline.ModelAnswer(text.toString(),
                usage.hasNonNull("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null,
                usage.hasNonNull("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null);
    }

    private String body(String prompt, List<DocumentExtractionRequest.Page> pages) {
        ObjectNode root = json.createObjectNode();
        ArrayNode parts = root.putArray("contents").addObject().put("role", "user").putArray("parts");
        parts.addObject().put("text", prompt);
        for (DocumentExtractionRequest.Page page : pages) {
            parts.addObject().putObject("inlineData").put("mimeType", page.mimeType())
                    .put("data", Base64.getEncoder().encodeToString(page.content()));
        }
        ObjectNode generation = root.putObject("generationConfig");
        generation.put("temperature", 0);
        generation.put("responseMimeType", "application/json");
        generation.set("responseSchema", json.readTree(pipeline.responseSchema()));
        return json.writeValueAsString(root);
    }

    private static DocumentIntelligenceException failure(Reason reason, DocumentExtractionResult.Provenance provenance,
            String message, Throwable cause) {
        return new DocumentIntelligenceException(reason, provenance, null, 0, message, cause);
    }
}
