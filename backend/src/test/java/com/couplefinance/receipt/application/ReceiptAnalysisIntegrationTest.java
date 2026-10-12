package com.couplefinance.receipt.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.ai.api.DocumentIntelligenceException.Reason;
import com.couplefinance.receipt.ReceiptFiles;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestDocumentIntelligence;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DOC-003 — asynchronous receipt analysis against PostgreSQL and MinIO with the fake provider: consent, queueing,
 * retry, explicit failure states, discarded late results, and the trust boundary (a draft, never an expense).
 * The worker is driven explicitly ({@code runOnce}); its schedule is disabled in the test profile.
 */
@IntegrationTest
class ReceiptAnalysisIntegrationTest {

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
    ReceiptAnalysisWorker worker;

    @Autowired
    TestDocumentIntelligence provider;

    @BeforeEach
    @AfterEach
    void isolate() {
        provider.reset();
        // Tests share the database: leave no queued job behind for the next test's worker run.
        jdbc.sql("UPDATE receipt.receipt_analysis_job SET status = 'CANCELLED' WHERE status = 'PENDING'").update();
    }

    @Test
    void BR_AI_05_without_consent_a_receipt_is_stored_but_never_queued_or_sent() {
        UUID user = users.active();
        createHousehold(user);
        int callsBefore = provider.calls();

        UUID receipt = upload(user);
        worker.runOnce();

        assertThat(state(user, receipt)).isEqualTo("UPLOADED");
        assertThat(count("receipt.receipt_analysis_job", receipt)).isZero();
        assertThat(provider.calls()).isEqualTo(callsBefore);
        assertThat(aiCalls(user)).isEmpty();
        assertThat(requestAnalysis(user, receipt)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("AI_CONSENT_REQUIRED");
        assertThat(receiptRow(receipt).get("ai_processed")).isEqualTo(false);
    }

    @Test
    void BR_RCP_10_a_consented_upload_is_analysed_into_a_draft_and_no_expense_is_created() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        consent(user, true);

        UUID receipt = upload(user);
        assertThat(state(user, receipt)).isEqualTo("QUEUED");
        assertThat(worker.runOnce()).isEqualTo(1);

        JsonNode view = get(user, receipt);
        assertThat(view.get("state").asString()).isEqualTo("READY_FOR_REVIEW");
        assertThat(view.get("manualRetriesLeft").asInt()).isEqualTo(3);
        assertThat(view.has("analysisFailureReason") && !view.get("analysisFailureReason").isNull()).isFalse();

        Map<String, Object> draft = jdbc.sql("SELECT total_minor, detected_currency, merchant_key, proposed_kind, "
                + "document_type, fields::text AS fields FROM receipt.receipt_draft WHERE receipt_id = :id")
                .param("id", receipt).query().singleRow();
        assertThat(draft.get("total_minor")).isEqualTo(1378L);
        assertThat(draft.get("detected_currency").toString().strip()).isEqualTo("EUR");
        assertThat(draft.get("merchant_key")).isEqualTo("carrefour");
        assertThat(draft.get("proposed_kind")).isEqualTo("EXPENSE");
        JsonNode fields = jsonMapper.readTree(draft.get("fields").toString());
        assertThat(fields.path("total").path("raw").asString()).isEqualTo("13,78");
        assertThat(fields.path("total").path("status").asString()).isEqualTo("OK");
        assertThat(jdbc.sql("SELECT amount_minor FROM receipt.receipt_line_item WHERE receipt_id = :id "
                + "ORDER BY position").param("id", receipt).query(Long.class).list())
                .containsExactly(129L, 110L, 890L, 249L);

        Map<String, Object> extraction = jdbc.sql("SELECT provider, prompt_version, outcome, raw_output IS NOT NULL "
                + "AS has_raw, raw_purge_at, created_at FROM receipt.receipt_extraction WHERE receipt_id = :id")
                .param("id", receipt).query().singleRow();
        assertThat(extraction.get("provider")).isEqualTo("fake");
        assertThat(extraction.get("prompt_version")).isEqualTo("receipt-extraction-v1");
        assertThat(extraction.get("outcome")).isEqualTo("SUCCEEDED");
        assertThat(extraction.get("has_raw")).isEqualTo(true);
        assertThat(jdbc.sql("SELECT raw_purge_at - created_at = interval '90 days' FROM receipt.receipt_extraction "
                + "WHERE receipt_id = :id").param("id", receipt).query(Boolean.class).single()).isTrue();

        assertThat(receiptRow(receipt).get("ai_processed")).isEqualTo(true);
        assertThat(jobStatuses(receipt)).containsExactly("SUCCEEDED");
        assertThat(aiCalls(user)).containsExactly("fake:OK");
        // AI output is untrusted: it only ever becomes a draft. No expense exists until the user confirms.
        assertThat(jdbc.sql("SELECT count(*) FROM expense.expense WHERE household_id = :h").param("h", household)
                .query(Long.class).single()).isZero();
    }

    @Test
    void BR_AI_07_the_call_log_holds_metadata_only() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        worker.runOnce();

        String row = jdbc.sql("SELECT row_to_json(c)::text FROM ai.ai_call c WHERE user_id = :u").param("u", user)
                .query(String.class).single();
        assertThat(row).contains("RECEIPT_EXTRACTION").doesNotContain("Carrefour").doesNotContain("13,78")
                .doesNotContain("receipts/");
        assertThat(jdbc.sql("SELECT correlation_id FROM ai.ai_call WHERE user_id = :u").param("u", user)
                .query(String.class).single()).isEqualTo(jdbc.sql("SELECT id::text FROM "
                        + "receipt.receipt_analysis_job WHERE receipt_id = :id").param("id", receipt)
                        .query(String.class).single());
    }

    @Test
    void BR_RCP_17_a_receipt_uploaded_before_consent_is_analysed_only_on_explicit_request() {
        UUID user = users.active();
        createHousehold(user);
        UUID receipt = upload(user);
        consent(user, true);
        worker.runOnce();
        assertThat(state(user, receipt)).as("consent is never applied retroactively").isEqualTo("UPLOADED");

        MvcTestResult requested = requestAnalysis(user, receipt);

        assertThat(requested).hasStatus(HttpStatus.ACCEPTED);
        assertThat(json(requested).get("state").asString()).isEqualTo("QUEUED");
        assertThat(json(requested).get("manualRetriesLeft").asInt()).isEqualTo(3);
        assertThat(requestAnalysis(user, receipt)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_ANALYSIS_NOT_ALLOWED");
        worker.runOnce();
        assertThat(state(user, receipt)).isEqualTo("READY_FOR_REVIEW");
        assertThat(requestAnalysis(user, receipt)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_ANALYSIS_NOT_ALLOWED");
        assertThat(jobStatuses(receipt)).containsExactly("SUCCEEDED");
    }

    @Test
    void transient_provider_failures_are_retried_automatically_and_then_succeed() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        provider.failNext(Reason.TIMEOUT, Reason.RATE_LIMITED);

        worker.runOnce();
        assertThat(state(user, receipt)).as("a retry is pending").isEqualTo("QUEUED");
        assertThat(job(receipt).get("attempt_count")).isEqualTo(1);
        assertThat(job(receipt).get("last_error_code")).isEqualTo("TIMEOUT");
        worker.runOnce();
        assertThat(state(user, receipt)).isEqualTo("QUEUED");
        assertThat(job(receipt).get("last_error_code")).isEqualTo("RATE_LIMITED");
        worker.runOnce();

        assertThat(state(user, receipt)).isEqualTo("READY_FOR_REVIEW");
        assertThat(jdbc.sql("SELECT outcome FROM receipt.receipt_extraction WHERE receipt_id = :id "
                + "ORDER BY attempt_no").param("id", receipt).query(String.class).list())
                .containsExactly("TIMEOUT", "PROVIDER_ERROR", "SUCCEEDED");
        assertThat(jdbc.sql("SELECT total_minor FROM receipt.receipt_draft WHERE receipt_id = :id")
                .param("id", receipt).query(Long.class).single()).isEqualTo(1378L);
    }

    @Test
    void BR_RCP_17_after_three_failed_attempts_the_analysis_fails_explicitly_and_stops() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        provider.failNext(Reason.PROVIDER_ERROR, Reason.PROVIDER_ERROR, Reason.TIMEOUT);

        worker.runOnce();
        worker.runOnce();
        worker.runOnce();

        JsonNode view = get(user, receipt);
        assertThat(view.get("state").asString()).isEqualTo("FAILED");
        assertThat(view.get("analysisFailureReason").asString()).isEqualTo("TIMEOUT");
        assertThat(job(receipt).get("status")).isEqualTo("FAILED");
        assertThat(job(receipt).get("attempt_count")).isEqualTo(3);
        int calls = provider.calls();
        assertThat(worker.runOnce()).as("a failed job is not picked up again").isZero();
        assertThat(provider.calls()).isEqualTo(calls);
        assertThat(count("receipt.receipt_draft", receipt)).isZero();
    }

    @Test
    void BR_RCP_17_a_schema_failure_is_not_retried_automatically_and_at_most_three_times_manually() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        provider.failNext(Reason.SCHEMA_INVALID, Reason.SCHEMA_INVALID, Reason.SCHEMA_INVALID,
                Reason.SCHEMA_INVALID);

        worker.runOnce();
        JsonNode failed = get(user, receipt);
        assertThat(failed.get("state").asString()).isEqualTo("FAILED");
        assertThat(failed.get("analysisFailureReason").asString()).isEqualTo("SCHEMA_INVALID");
        assertThat(job(receipt).get("attempt_count")).isEqualTo(1);
        assertThat(count("receipt.receipt_draft", receipt)).as("invalid output is never parsed").isZero();

        for (int left = 2; left >= 0; left--) {
            MvcTestResult retry = requestAnalysis(user, receipt);
            assertThat(retry).hasStatus(HttpStatus.ACCEPTED);
            assertThat(json(retry).get("manualRetriesLeft").asInt()).isEqualTo(left);
            worker.runOnce();
            assertThat(state(user, receipt)).isEqualTo("FAILED");
        }
        assertThat(requestAnalysis(user, receipt)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_ANALYSIS_RETRY_LIMIT");
        assertThat(jobStatuses(receipt)).containsExactly("FAILED", "FAILED", "FAILED", "FAILED");
    }

    @Test
    void a_manual_retry_after_a_failure_can_succeed() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        provider.failNext(Reason.REFUSED);
        worker.runOnce();
        assertThat(get(user, receipt).get("analysisFailureReason").asString()).isEqualTo("PROVIDER_ERROR");

        assertThat(requestAnalysis(user, receipt)).hasStatus(HttpStatus.ACCEPTED);
        worker.runOnce();

        JsonNode view = get(user, receipt);
        assertThat(view.get("state").asString()).isEqualTo("READY_FOR_REVIEW");
        assertThat(view.get("manualRetriesLeft").asInt()).isEqualTo(2);
        assertThat(view.has("analysisFailureReason") && !view.get("analysisFailureReason").isNull()).isFalse();
    }

    @Test
    void BR_AI_05_withdrawing_consent_cancels_queued_analyses_immediately() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        int callsBefore = provider.calls();

        consent(user, false);

        assertThat(state(user, receipt)).isEqualTo("UPLOADED");
        assertThat(jobStatuses(receipt)).containsExactly("CANCELLED");
        worker.runOnce();
        assertThat(provider.calls()).isEqualTo(callsBefore);
        assertThat(aiCalls(user)).isEmpty();
        assertThat(requestAnalysis(user, receipt)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("AI_CONSENT_REQUIRED");
    }

    @Test
    void BR_AI_05_consent_is_rechecked_when_the_job_runs() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        // A withdrawal whose event was not (yet) handled: the job is still queued.
        jdbc.sql("INSERT INTO identity.ai_consent_event (id, user_id, purpose, action, consent_text_version, "
                + "occurred_at) VALUES (:id, :user, 'RECEIPT_ANALYSIS', 'WITHDRAW', 'receipt-analysis-v1', "
                + "now() + interval '1 second')").param("id", UUID.randomUUID()).param("user", user).update();
        int callsBefore = provider.calls();

        worker.runOnce();

        assertThat(provider.calls()).isEqualTo(callsBefore);
        assertThat(state(user, receipt)).isEqualTo("UPLOADED");
        assertThat(jobStatuses(receipt)).containsExactly("CANCELLED");
        assertThat(count("receipt.receipt_draft", receipt)).isZero();
    }

    @Test
    void a_result_arriving_after_the_receipt_was_deleted_is_discarded() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        provider.duringNextCall(() -> assertThat(mvc.delete().uri("/api/v1/receipts/" + receipt)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange())
                .hasStatus(HttpStatus.NO_CONTENT));

        worker.runOnce();

        assertThat(count("receipt.receipt", "id", receipt)).isZero();
        assertThat(count("receipt.receipt_draft", receipt)).isZero();
        assertThat(count("receipt.receipt_extraction", receipt)).isZero();
        assertThat(count("receipt.receipt_analysis_job", receipt)).isZero();
    }

    @Test
    void a_result_arriving_after_the_receipt_left_the_open_state_is_discarded() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        // Linking a receipt (DOC-004) ends its live analysis: ck_receipt_closed_no_live_analysis.
        provider.duringNextCall(() -> jdbc.sql("UPDATE receipt.receipt SET status = 'ATTACHED', linked_at = now(), "
                + "analysis_status = 'CANCELLED' WHERE id = :id").param("id", receipt).update());

        worker.runOnce();

        assertThat(count("receipt.receipt_draft", receipt)).isZero();
        assertThat(jobStatuses(receipt)).containsExactly("CANCELLED");
    }

    @Test
    void a_job_abandoned_by_a_crashed_worker_is_picked_up_again_after_its_lease() {
        UUID user = users.active();
        createHousehold(user);
        consent(user, true);
        UUID receipt = upload(user);
        // A worker claimed the job and died: RUNNING with an expired lease.
        jdbc.sql("UPDATE receipt.receipt_analysis_job SET status = 'RUNNING', locked_by = 'dead-worker', "
                + "locked_until = now() - interval '1 minute' WHERE receipt_id = :id").param("id", receipt).update();
        jdbc.sql("UPDATE receipt.receipt SET analysis_status = 'RUNNING' WHERE id = :id").param("id", receipt)
                .update();
        assertThat(state(user, receipt)).isEqualTo("ANALYSING");

        worker.runOnce();

        assertThat(state(user, receipt)).isEqualTo("READY_FOR_REVIEW");
        assertThat(jobStatuses(receipt)).containsExactly("SUCCEEDED");
    }

    @Test
    void only_the_uploader_can_request_an_analysis() {
        UUID owner = users.active();
        UUID household = createHousehold(owner);
        UUID partner = users.active();
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", partner)
                .update();
        UUID stranger = users.active();
        createHousehold(stranger);
        consent(partner, true);
        consent(stranger, true);
        UUID receipt = upload(owner);

        assertThat(requestAnalysis(partner, receipt)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_NOT_FOUND");
        assertThat(requestAnalysis(stranger, receipt)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/v1/receipts/" + receipt + "/analysis").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(count("receipt.receipt_analysis_job", receipt)).isZero();
    }

    @Test
    void BR_HH_10_no_analysis_is_requested_on_a_dissolved_household() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = upload(user);
        consent(user, true);
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();

        assertThat(requestAnalysis(user, receipt)).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
    }

    // ------------------------------------------------------------------ helpers

    private UUID upload(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/receipts").multipart()
                .file(new MockMultipartFile("files", "receipt", "application/octet-stream",
                        ReceiptFiles.jpeg(120, 200)))
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private void consent(UUID user, boolean granted) {
        assertThat(mvc.put().uri("/api/v1/me/ai-consents/RECEIPT_ANALYSIS")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"granted\":" + granted + ",\"consentTextVersion\":\"receipt-analysis-v1\"}").exchange())
                .hasStatus(HttpStatus.OK);
    }

    private MvcTestResult requestAnalysis(UUID user, UUID receipt) {
        return mvc.post().uri("/api/v1/receipts/" + receipt + "/analysis")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private JsonNode get(UUID user, UUID receipt) {
        MvcTestResult result = mvc.get().uri("/api/v1/receipts/" + receipt)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return json(result);
    }

    private String state(UUID user, UUID receipt) {
        return get(user, receipt).get("state").asString();
    }

    private Map<String, Object> receiptRow(UUID receipt) {
        return jdbc.sql("SELECT * FROM receipt.receipt WHERE id = :id").param("id", receipt).query().singleRow();
    }

    /** The latest job of a receipt. */
    private Map<String, Object> job(UUID receipt) {
        return jdbc.sql("SELECT * FROM receipt.receipt_analysis_job WHERE receipt_id = :id ORDER BY created_at DESC, "
                + "id DESC LIMIT 1").param("id", receipt).query().singleRow();
    }

    private List<String> jobStatuses(UUID receipt) {
        return jdbc.sql("SELECT status FROM receipt.receipt_analysis_job WHERE receipt_id = :id ORDER BY created_at, "
                + "id").param("id", receipt).query(String.class).list();
    }

    private List<String> aiCalls(UUID user) {
        return jdbc.sql("SELECT provider || ':' || outcome FROM ai.ai_call WHERE user_id = :u ORDER BY created_at, id")
                .param("u", user).query(String.class).list();
    }

    private long count(String table, UUID receipt) {
        return count(table, "receipt_id", receipt);
    }

    private long count(String table, String column, UUID receipt) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id").param("id", receipt)
                .query(Long.class).single();
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
