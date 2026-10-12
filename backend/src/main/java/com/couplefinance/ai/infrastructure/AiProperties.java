package com.couplefinance.ai.infrastructure;

import java.net.URI;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI configuration (ADR-008). Secrets (API key, service-account credentials) come from the environment only.
 *
 * @param provider       {@code fake} (default) or {@code gemini}
 * @param maxConcurrency bound on simultaneous outbound extraction calls (ai.md §8)
 */
@ConfigurationProperties("couplefinance.ai")
public record AiProperties(DocumentIntelligence documentIntelligence, Gemini gemini) {

    public record DocumentIntelligence(String provider, int maxConcurrency, Duration timeout) {
    }

    /**
     * @param endpointMode     {@code vertex} (production, EU region) or {@code api-key} (development, paid tier)
     * @param privacyApproved  must be {@code true} for the Gemini adapter to start (ADR-008 §4)
     * @param apiKeyTier       {@code paid} or {@code free}; {@code free} is always refused
     * @param baseUrl          override of the endpoint root (tests); {@code null} = Google endpoints
     */
    public record Gemini(String endpointMode, String model, @Nullable String apiKey, @Nullable String apiKeyTier,
            @Nullable String project, @Nullable String location, @Nullable URI baseUrl, boolean privacyApproved) {
    }
}
