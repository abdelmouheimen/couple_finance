package com.couplefinance.ai.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.couplefinance.ai.api.DocumentIntelligencePort;
import com.couplefinance.identity.api.AiConsents;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provider selection (ADR-008): {@code couplefinance.ai.document-intelligence.provider = fake | gemini}. Business
 * modules only ever receive the consent-guarded {@link DocumentIntelligencePort}. The Gemini adapter refuses to start
 * without an approved privacy configuration (fail-fast).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    static final String GEMINI_ROOT = "https://generativelanguage.googleapis.com";
    static final String CLOUD_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    @Bean
    ExtractionPipeline extractionPipeline(JsonMapper json, AiCallLog callLog, Clock clock) {
        return new ExtractionPipeline(new ExtractionSchemaValidator(json), callLog, clock);
    }

    @Bean
    DocumentIntelligencePort documentIntelligencePort(AiProperties properties, ExtractionPipeline pipeline,
            JsonMapper json, AiConsents consents, AiCallLog callLog) {
        String provider = properties.documentIntelligence().provider();
        DocumentIntelligencePort adapter = switch (provider) {
            case FakeDocumentIntelligenceAdapter.PROVIDER -> new FakeDocumentIntelligenceAdapter(pipeline);
            case GeminiDocumentIntelligenceAdapter.PROVIDER -> gemini(properties, pipeline, json);
            default -> throw new IllegalStateException("Unknown document-intelligence provider: " + provider);
        };
        return new ConsentGuardedDocumentIntelligence(adapter, provider, consents, callLog,
                properties.documentIntelligence().maxConcurrency());
    }

    static GeminiDocumentIntelligenceAdapter gemini(AiProperties properties, ExtractionPipeline pipeline,
            JsonMapper json) {
        AiProperties.Gemini gemini = properties.gemini();
        requirePrivacyApproved(gemini);
        Duration timeout = properties.documentIntelligence().timeout();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        boolean apiKeyMode = "api-key".equals(gemini.endpointMode());
        URI endpoint;
        Supplier<String> authorization;
        if (apiKeyMode) {
            String root = gemini.baseUrl() != null ? gemini.baseUrl().toString() : GEMINI_ROOT;
            endpoint = URI.create(root + "/v1beta/models/" + gemini.model() + ":generateContent");
            String key = gemini.apiKey();
            authorization = () -> key;
        } else {
            String root = gemini.baseUrl() != null ? gemini.baseUrl().toString()
                    : "https://" + gemini.location() + "-aiplatform.googleapis.com";
            endpoint = URI.create(root + "/v1/projects/" + gemini.project() + "/locations/" + gemini.location()
                    + "/publishers/google/models/" + gemini.model() + ":generateContent");
            authorization = vertexAccessTokens();
        }
        return new GeminiDocumentIntelligenceAdapter(pipeline, http, json, endpoint, apiKeyMode, authorization,
                timeout, gemini.model());
    }

    /** ADR-008 §4: refuse every configuration that could send production documents to an unapproved endpoint. */
    static void requirePrivacyApproved(AiProperties.Gemini gemini) {
        if (!gemini.privacyApproved()) {
            throw new IllegalStateException("Gemini provider refused: couplefinance.ai.gemini.privacy-approved is "
                    + "not true (ADR-008: approved privacy configuration and user disclosure required)");
        }
        switch (gemini.endpointMode()) {
            case "vertex" -> {
                if (blank(gemini.project()) || blank(gemini.location()) || !gemini.location().startsWith("europe-")) {
                    throw new IllegalStateException("Gemini (Vertex AI) refused: a project and an EU location "
                            + "(europe-*) are required (ADR-005 §6, ADR-008)");
                }
            }
            case "api-key" -> {
                if (blank(gemini.apiKey())) {
                    throw new IllegalStateException("Gemini (API key) refused: COUPLEFINANCE_AI_GEMINI_API_KEY unset");
                }
                if (!"paid".equals(gemini.apiKeyTier())) {
                    throw new IllegalStateException("Gemini (API key) refused: only a paid tier key may be used "
                            + "(couplefinance.ai.gemini.api-key-tier=paid); the free tier is never allowed (ADR-008)");
                }
            }
            default -> throw new IllegalStateException("Unknown Gemini endpoint mode: " + gemini.endpointMode());
        }
        if (blank(gemini.model())) {
            throw new IllegalStateException("Gemini model is required");
        }
    }

    /** Application Default Credentials (service account), refreshed when expired. */
    private static Supplier<String> vertexAccessTokens() {
        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(CLOUD_SCOPE));
        } catch (IOException e) {
            throw new UncheckedIOException("Vertex AI credentials not found (GOOGLE_APPLICATION_CREDENTIALS)", e);
        }
        return () -> {
            try {
                credentials.refreshIfExpired();
                return credentials.getAccessToken().getTokenValue();
            } catch (IOException e) {
                throw new UncheckedIOException("Vertex AI access token refresh failed", e);
            }
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
