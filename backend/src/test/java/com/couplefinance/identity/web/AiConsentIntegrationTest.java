package com.couplefinance.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.identity.api.AiConsents;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DOC-003 — AI consents (ADR-005, BR-AI-05): explicit, per user and purpose, withdrawable, recorded. */
@IntegrationTest
class AiConsentIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    AiConsents consents;

    @Test
    void BR_AI_05_consent_is_never_inferred_a_new_user_has_granted_nothing() {
        UUID user = users.active();

        JsonNode list = json(mvc.get().uri("/api/v1/me/ai-consents")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange());

        assertThat(list.get("items")).hasSize(AiConsentPurpose.values().length)
                .allSatisfy(item -> assertThat(item.get("granted").asBoolean()).isFalse());
        for (AiConsentPurpose purpose : AiConsentPurpose.values()) {
            assertThat(consents.isGranted(new UserId(user), purpose)).isFalse();
        }
    }

    @Test
    void BR_AI_05_a_consent_is_granted_per_purpose_and_per_user_and_can_be_withdrawn() {
        UUID user = users.active();
        UUID partner = users.active();

        MvcTestResult granted = decide(user, "RECEIPT_ANALYSIS", true, "receipt-analysis-v1");

        assertThat(granted).hasStatus(HttpStatus.OK);
        assertThat(json(granted).get("granted").asBoolean()).isTrue();
        assertThat(json(granted).get("consentTextVersion").asString()).isEqualTo("receipt-analysis-v1");
        assertThat(consents.isGranted(new UserId(user), AiConsentPurpose.RECEIPT_ANALYSIS)).isTrue();
        assertThat(consents.isGranted(new UserId(user), AiConsentPurpose.INSIGHTS_ASSISTANT)).isFalse();
        assertThat(consents.isGranted(new UserId(user), AiConsentPurpose.QUALITY_DATASET)).isFalse();
        assertThat(consents.isGranted(new UserId(partner), AiConsentPurpose.RECEIPT_ANALYSIS)).isFalse();
        JsonNode list = json(mvc.get().uri("/api/v1/me/ai-consents")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange());
        assertThat(list.get("items")).filteredOn(item -> item.get("granted").asBoolean()).singleElement()
                .satisfies(item -> assertThat(item.get("purpose").asString()).isEqualTo("RECEIPT_ANALYSIS"));

        assertThat(decide(user, "RECEIPT_ANALYSIS", false, "receipt-analysis-v1")).hasStatus(HttpStatus.OK);

        assertThat(consents.isGranted(new UserId(user), AiConsentPurpose.RECEIPT_ANALYSIS)).isFalse();
        assertThat(actions(user)).containsExactly("GRANT", "WITHDRAW");
    }

    @Test
    void every_decision_is_recorded_with_the_wording_version_shown() {
        UUID user = users.active();

        decide(user, "RECEIPT_ANALYSIS", true, "receipt-analysis-v1");
        decide(user, "RECEIPT_ANALYSIS", true, " receipt-analysis-v2 ");

        assertThat(jdbc.sql("SELECT consent_text_version FROM identity.ai_consent_event WHERE user_id = :u "
                + "ORDER BY occurred_at, id").param("u", user).query(String.class).list())
                .containsExactly("receipt-analysis-v1", "receipt-analysis-v2");
    }

    @Test
    void invalid_decisions_are_rejected_and_nothing_is_recorded() {
        UUID user = users.active();

        assertThat(put(user, "RECEIPT_ANALYSIS", "{\"granted\":true}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put(user, "RECEIPT_ANALYSIS", "{\"consentTextVersion\":\"v1\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(decide(user, "RECEIPT_ANALYSIS", true, "x".repeat(41))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(decide(user, "EVERYTHING", true, "v1").getResponse().getStatus()).isBetween(400, 404);

        assertThat(actions(user)).isEmpty();
    }

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.get().uri("/api/v1/me/ai-consents").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.put().uri("/api/v1/me/ai-consents/RECEIPT_ANALYSIS").contentType(MediaType.APPLICATION_JSON)
                .content("{\"granted\":true,\"consentTextVersion\":\"v1\"}").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult decide(UUID user, String purpose, boolean granted, String version) {
        return put(user, purpose, "{\"granted\":" + granted + ",\"consentTextVersion\":\"" + version + "\"}");
    }

    private MvcTestResult put(UUID user, String purpose, String body) {
        return mvc.put().uri("/api/v1/me/ai-consents/" + purpose)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private List<String> actions(UUID user) {
        return jdbc.sql("SELECT action FROM identity.ai_consent_event WHERE user_id = :u ORDER BY occurred_at, id")
                .param("u", user).query(String.class).list();
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
