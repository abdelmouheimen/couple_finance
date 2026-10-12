package com.couplefinance.ai.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligenceException;
import com.couplefinance.ai.api.DocumentIntelligenceException.Reason;
import com.couplefinance.ai.api.DocumentIntelligencePort;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini adapter against a mocked Gemini REST API (WireMock): the shared port contract plus the Gemini wire format
 * and every failure mode. Never calls a real provider (CLAUDE.md §13).
 */
class GeminiDocumentIntelligenceAdapterTest extends DocumentIntelligencePortContract {

    private static final String MODEL = "gemini-test";
    private static final String API_KEY_PATH = "/v1beta/models/" + MODEL + ":generateContent";
    private static final String TEST_KEY = "test-key-not-a-secret";

    private WireMockServer gemini;

    @BeforeEach
    void start() {
        gemini = new WireMockServer(options().dynamicPort());
        gemini.start();
    }

    @AfterEach
    void stop() {
        gemini.stop();
    }

    private AiProperties properties(Duration timeout) {
        return new AiProperties(new AiProperties.DocumentIntelligence("gemini", 2, timeout),
                new AiProperties.Gemini("api-key", MODEL, TEST_KEY, "paid", null, null,
                        URI.create(gemini.baseUrl()), true));
    }

    private DocumentIntelligencePort adapter() {
        return AiConfiguration.gemini(properties(Duration.ofSeconds(10)), pipeline, json);
    }

    /** A Gemini generateContent answer whose single candidate carries {@code modelOutput} as text. */
    private String geminiAnswer(String modelOutput) {
        ObjectNode root = json.createObjectNode();
        ObjectNode candidate = root.putArray("candidates").addObject();
        candidate.putObject("content").put("role", "model").putArray("parts").addObject().put("text", modelOutput);
        candidate.put("finishReason", "STOP");
        root.putObject("usageMetadata").put("promptTokenCount", 1200).put("candidatesTokenCount", 340);
        return json.writeValueAsString(root);
    }

    private static ResponseDefinitionBuilder ok(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    @Override
    DocumentIntelligencePort adapterAnswering(String... outputs) {
        for (int i = 0; i < outputs.length; i++) {
            gemini.stubFor(post(urlPathEqualTo(API_KEY_PATH)).inScenario("answers")
                    .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "call-" + i)
                    .willSetStateTo("call-" + (i + 1))
                    .willReturn(ok(geminiAnswer(outputs[i]))));
        }
        return adapter();
    }

    @Override
    int providerCalls() {
        return gemini.countRequestsMatching(anyRequestedFor(anyUrl()).build()).getCount();
    }

    @Override
    String providerName() {
        return "gemini";
    }

    private void stub(ResponseDefinitionBuilder response) {
        gemini.stubFor(post(urlPathEqualTo(API_KEY_PATH)).willReturn(response));
    }

    private static void assertFails(DocumentIntelligencePort adapter, Reason reason, boolean retryable) {
        assertThatThrownBy(() -> adapter.extract(request()))
                .isInstanceOfSatisfying(DocumentIntelligenceException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.reason().retryable()).isEqualTo(retryable);
                    assertThat(e.provenance()).isNotNull();
                    assertThat(e.getMessage()).doesNotContain(TEST_KEY);
                });
    }

    @Test
    void BR_AI_06_only_the_prompt_the_pages_and_the_schema_are_sent_with_the_key_in_a_header() {
        stub(ok(geminiAnswer(FakeDocumentIntelligenceAdapter.FIXTURE)));
        DocumentExtractionRequest request = request();

        DocumentExtractionResult result = adapter().extract(request);

        assertThat(result.provenance().model()).isEqualTo(MODEL);
        gemini.verify(postRequestedFor(urlPathEqualTo(API_KEY_PATH)).withHeader("x-goog-api-key", equalTo(TEST_KEY)));
        var sent = gemini.getAllServeEvents().get(0).getRequest();
        assertThat(sent.getUrl()).doesNotContain(TEST_KEY).doesNotContain("key=");
        String body = sent.getBodyAsString();
        assertThat(body).doesNotContain(request.consentingUser().toString())
                .doesNotContain(request.householdId().toString()).doesNotContain(request.correlationId());
        JsonNode root = json.readTree(body);
        JsonNode parts = root.path("contents").path(0).path("parts");
        assertThat(parts).hasSize(2);
        assertThat(parts.path(0).path("text").asString()).contains("EUR").contains("fr-FR");
        assertThat(parts.path(1).path("inlineData").path("mimeType").asString()).isEqualTo("image/jpeg");
        assertThat(Base64.getDecoder().decode(parts.path(1).path("inlineData").path("data").asString()))
                .isEqualTo(request.pages().get(0).content());
        JsonNode generation = root.path("generationConfig");
        assertThat(generation.path("temperature").asInt()).isZero();
        assertThat(generation.path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(generation.path("responseSchema").path("type").asString()).isNotEmpty();
        assertThat(callLog.calls).singleElement().satisfies(call -> {
            assertThat(call.provider()).isEqualTo("gemini");
            assertThat(call.model()).isEqualTo(MODEL);
            assertThat(call.tokensIn()).isEqualTo(1200);
            assertThat(call.tokensOut()).isEqualTo(340);
        });
    }

    @Test
    void vertex_mode_calls_the_regional_endpoint_with_a_bearer_token() {
        AiProperties.Gemini vertex = new AiProperties.Gemini("vertex", MODEL, null, null, "cf-prod", "europe-west1",
                URI.create(gemini.baseUrl()), true);
        String path = "/v1/projects/cf-prod/locations/europe-west1/publishers/google/models/" + MODEL
                + ":generateContent";
        assertThat(AiConfiguration.endpoint(vertex).getPath()).isEqualTo(path);
        assertThat(AiConfiguration.endpoint(new AiProperties.Gemini("vertex", MODEL, null, null, "cf-prod",
                "europe-west1", null, true)).getHost()).isEqualTo("europe-west1-aiplatform.googleapis.com");
        gemini.stubFor(post(urlPathEqualTo(path)).willReturn(ok(geminiAnswer(FakeDocumentIntelligenceAdapter.FIXTURE))));
        DocumentIntelligencePort adapter = new GeminiDocumentIntelligenceAdapter(pipeline,
                HttpClient.newHttpClient(), json, AiConfiguration.endpoint(vertex), false, () -> "access-token",
                Duration.ofSeconds(10), MODEL);

        assertThat(adapter.extract(request()).extraction().total()).contains("13,78");
        gemini.verify(postRequestedFor(urlPathEqualTo(path)).withHeader("Authorization",
                equalTo("Bearer access-token")));
    }

    @Test
    void a_rate_limit_is_a_retryable_failure() {
        stub(aResponse().withStatus(429).withBody("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        assertFails(adapter(), Reason.RATE_LIMITED, true);
        assertThat(callLog.outcomes()).containsExactly(AiCallLog.Outcome.ERROR);
    }

    @Test
    void a_provider_error_is_a_retryable_failure() {
        stub(aResponse().withStatus(503));
        assertFails(adapter(), Reason.PROVIDER_ERROR, true);
        assertThat(providerCalls()).isEqualTo(1);
    }

    @Test
    void a_rejected_request_is_a_provider_error_without_leaking_the_key() {
        stub(aResponse().withStatus(403).withBody("{\"error\":{\"message\":\"API key " + TEST_KEY + " invalid\"}}"));
        assertFails(adapter(), Reason.PROVIDER_ERROR, true);
    }

    @Test
    void a_slow_answer_times_out() {
        stub(ok(geminiAnswer(FakeDocumentIntelligenceAdapter.FIXTURE)).withFixedDelay(2_000));
        DocumentIntelligencePort adapter = AiConfiguration.gemini(properties(Duration.ofMillis(300)), pipeline, json);
        assertFails(adapter, Reason.TIMEOUT, true);
        assertThat(callLog.outcomes()).containsExactly(AiCallLog.Outcome.TIMEOUT);
    }

    @Test
    void a_gateway_timeout_is_a_timeout() {
        stub(aResponse().withStatus(504));
        assertFails(adapter(), Reason.TIMEOUT, true);
    }

    @Test
    void a_dropped_connection_is_a_retryable_provider_error() {
        stub(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
        assertFails(adapter(), Reason.PROVIDER_ERROR, true);
    }

    @Test
    void a_blocked_prompt_or_a_safety_stop_is_a_refusal_not_retried() {
        stub(ok("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));
        assertFails(adapter(), Reason.REFUSED, false);

        gemini.resetAll();
        stub(ok("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}"));
        assertFails(adapter(), Reason.REFUSED, false);

        gemini.resetAll();
        stub(ok("{\"candidates\":[]}"));
        assertFails(adapter(), Reason.REFUSED, false);
        assertThat(callLog.outcomes()).containsOnly(AiCallLog.Outcome.REFUSED);
    }

    @Test
    void a_non_json_envelope_is_a_provider_error() {
        stub(ok("<html>bad gateway</html>"));
        assertFails(adapter(), Reason.PROVIDER_ERROR, true);
    }

    @Test
    void unavailable_credentials_fail_without_calling_the_provider() {
        DocumentIntelligencePort adapter = new GeminiDocumentIntelligenceAdapter(pipeline,
                HttpClient.newHttpClient(), json, URI.create(gemini.baseUrl() + API_KEY_PATH), false, () -> {
                    throw new IllegalStateException("no credentials");
                }, Duration.ofSeconds(5), MODEL);
        assertFails(adapter, Reason.PROVIDER_ERROR, true);
        assertThat(providerCalls()).isZero();
    }
}
