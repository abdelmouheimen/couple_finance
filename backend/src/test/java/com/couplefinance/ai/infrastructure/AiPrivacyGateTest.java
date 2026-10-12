package com.couplefinance.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ADR-008 §4: the Gemini adapter refuses to start on any configuration that could send users' documents to an
 * unapproved endpoint — in particular the free tier, a non-EU region, or without the explicit privacy approval.
 */
class AiPrivacyGateTest {

    private static AiProperties.Gemini apiKey(String key, String tier, boolean approved) {
        return new AiProperties.Gemini("api-key", "gemini-2.5-flash", key, tier, null, null, null, approved);
    }

    private static AiProperties.Gemini vertex(String project, String location, boolean approved) {
        return new AiProperties.Gemini("vertex", "gemini-2.5-flash", null, null, project, location, null, approved);
    }

    @Test
    void gemini_is_refused_without_the_explicit_privacy_approval() {
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(apiKey("k", "paid", false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("privacy-approved");
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(vertex("p", "europe-west1", false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("privacy-approved");
    }

    @Test
    void the_free_tier_is_never_allowed() {
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(apiKey("k", "free", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("free tier");
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(apiKey("k", null, true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("paid");
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(apiKey(" ", "paid", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("API_KEY");
    }

    @Test
    void vertex_requires_a_project_and_an_eu_region() {
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(vertex("p", "us-central1", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EU location");
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(vertex(null, "europe-west1", true)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(vertex("p", null, true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknown_modes_and_missing_models_are_refused() {
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(new AiProperties.Gemini("openrouter",
                "m", "k", "paid", null, null, null, true))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AiConfiguration.requirePrivacyApproved(new AiProperties.Gemini("api-key", " ", "k",
                "paid", null, null, null, true))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }

    @Test
    void approved_configurations_start() {
        assertThatCode(() -> AiConfiguration.requirePrivacyApproved(apiKey("k", "paid", true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AiConfiguration.requirePrivacyApproved(vertex("p", "europe-west1", true)))
                .doesNotThrowAnyException();
    }
}
