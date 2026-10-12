package com.couplefinance.receipt.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.receipt.ReceiptFiles;
import com.couplefinance.receipt.application.ReceiptAnalysisWorker;
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
 * DOC-004 — review, duplicate warnings and exactly-once confirmation of a receipt, against PostgreSQL and MinIO with
 * the fake provider (BR-RCP-02, BR-RCP-10, BR-RCP-14, BR-RCP-15, BR-RCP-16, BR-RCP-18, ADR-006).
 */
@IntegrationTest
class ReceiptConfirmationIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    /** A date every run accepts for an expense (BR-EXP-06 window is relative to today). */
    private static final LocalDate DATE = LocalDate.now(ZoneId.of("Europe/Paris")).minusDays(2);

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
        jdbc.sql("UPDATE receipt.receipt_analysis_job SET status = 'CANCELLED' WHERE status = 'PENDING'").update();
    }

    // ------------------------------------------------------------------ review

    @Test
    void BR_RCP_10_the_review_presents_the_draft_as_proposals_with_statuses() {
        UUID user = users.active();
        createHousehold(user);
        UUID receipt = analysed(user);

        JsonNode review = review(user, receipt);

        assertThat(review.get("analysed").asBoolean()).isTrue();
        assertThat(review.get("state").asString()).isEqualTo("READY_FOR_REVIEW");
        assertThat(review.get("proposedMerchant").asString()).isEqualTo("Carrefour");
        assertThat(review.get("proposedTotal").get("amount").asString()).isEqualTo("13.78");
        assertThat(review.get("proposedTotal").get("amount").isString()).as("money is never a JSON number").isTrue();
        assertThat(review.get("proposedTotal").get("currency").asString()).isEqualTo("EUR");
        assertThat(review.get("proposedDate").asString()).isEqualTo("2026-10-04");
        assertThat(review.get("detectedCurrency").asString()).isEqualTo("EUR");
        assertThat(review.get("refundProposed").asBoolean()).isFalse();
        assertThat(review.get("fields").get("total").get("raw").asString()).isEqualTo("13,78");
        assertThat(review.get("fields").get("total").get("status").asString()).isEqualTo("OK");
        assertThat(review.get("unresolvedFields")).isEmpty();
        assertThat(review.get("lines")).hasSize(4);
        assertThat(review.get("lines").get(0).get("amount").get("amount").asString()).isEqualTo("1.29");
        assertThat(review.get("duplicates")).isEmpty();
        assertThat(review.get("version").asLong()).isEqualTo(version(receipt));
    }

    @Test
    void BR_CAT_04_the_category_is_suggested_from_the_merchant_through_the_households_rules() {
        UUID user = users.active();
        createHousehold(user);
        UUID receipt = analysed(user);

        JsonNode fallback = review(user, receipt).get("suggestedCategory");
        assertThat(fallback.get("source").asString()).isEqualTo("DEFAULT_OTHER");
        assertThat(usableCategories(user)).contains(fallback.get("categoryId").asString());

        assertThat(mvc.put().uri("/api/v1/merchant-rules").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"merchant\":\"Carrefour\",\"categoryId\":\""
                        + GROCERIES + "\",\"sharingType\":\"SHARED\"}").exchange()).hasStatus(HttpStatus.OK);

        JsonNode learned = review(user, receipt).get("suggestedCategory");
        assertThat(learned.get("categoryId").asString()).isEqualTo(GROCERIES);
        assertThat(learned.get("source").asString()).isEqualTo("HOUSEHOLD_RULE");
    }

    @Test
    void a_receipt_never_analysed_is_reviewable_as_a_manual_entry() {
        UUID user = users.active();
        createHousehold(user);
        UUID receipt = upload(user, ReceiptFiles.jpeg(120, 200));

        JsonNode review = review(user, receipt);

        assertThat(review.get("analysed").asBoolean()).isFalse();
        assertThat(review.get("state").asString()).isEqualTo("UPLOADED");
        assertThat(absent(review, "proposedTotal")).isTrue();
        assertThat(absent(review, "suggestedCategory")).isTrue();
        assertThat(review.get("fields")).isEmpty();
    }

    // ------------------------------------------------------------------ confirmation

    @Test
    void BR_RCP_02_confirming_creates_one_expense_linked_to_the_receipt() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = analysed(user);
        assertThat(expenseCount(household)).as("no expense before the member confirms").isZero();

        MvcTestResult result = confirm(user, receipt, "key-1", body(user, version(receipt), "13.78", "13.78"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode link = json(result);
        assertThat(link.get("state").asString()).isEqualTo("CONFIRMED");
        UUID expense = UUID.fromString(link.get("expenseId").asString());

        Map<String, Object> row = jdbc.sql("SELECT amount_minor, currency, expense_date, source, receipt_id, "
                + "merchant_display, owner_user_id FROM expense.expense WHERE id = :id").param("id", expense)
                .query().singleRow();
        assertThat(row.get("amount_minor")).isEqualTo(1378L);
        assertThat(row.get("currency").toString().strip()).isEqualTo("EUR");
        assertThat(row.get("source")).isEqualTo("RECEIPT");
        assertThat(row.get("receipt_id")).isEqualTo(receipt);
        assertThat(row.get("merchant_display")).isEqualTo("Carrefour");
        assertThat(row.get("owner_user_id")).isNull();
        assertThat(jdbc.sql("SELECT amount_minor FROM expense.expense_item WHERE expense_id = :id")
                .param("id", expense).query(Long.class).list()).containsExactly(1378L);

        JsonNode view = json(mvc.get().uri("/api/v1/receipts/" + receipt)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange());
        assertThat(view.get("state").asString()).isEqualTo("CONFIRMED");
        assertThat(view.get("expenseId").asString()).isEqualTo(expense.toString());
        JsonNode expenseView = json(mvc.get().uri("/api/v1/expenses/" + expense)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange());
        assertThat(expenseView.get("receiptId").asString()).isEqualTo(receipt.toString());
        assertThat(expenseView.get("source").asString()).isEqualTo("RECEIPT");
        assertThat(expenseView.get("amount").get("amount").asString()).isEqualTo("13.78");

        Map<String, Object> snapshot = jdbc.sql("SELECT mode, confirmed_by, acknowledged_total_minor, "
                + "acknowledged_date, idempotency_key FROM receipt.receipt_confirmation WHERE receipt_id = :id")
                .param("id", receipt).query().singleRow();
        assertThat(snapshot.get("mode")).isEqualTo("NEW_EXPENSE");
        assertThat(snapshot.get("confirmed_by")).isEqualTo(user);
        assertThat(snapshot.get("acknowledged_total_minor")).isEqualTo(1378L);
        assertThat(snapshot.get("idempotency_key")).isEqualTo("key-1");
        assertThat(jdbc.sql("SELECT action FROM receipt.audit_event WHERE entity_id = :id ORDER BY occurred_at, id")
                .param("id", receipt).query(String.class).list()).containsExactly("UPLOAD", "CONFIRM");
        assertThat(expenseCount(household)).isEqualTo(1L);
    }

    @Test
    void BR_RCP_18_the_confirmed_amount_is_the_members_value_and_the_ai_output_is_kept_apart() {
        UUID user = users.active();
        createHousehold(user);
        UUID receipt = analysed(user);

        // The AI read 13,78; the member read 14,78 on the paper and acknowledges it.
        MvcTestResult result = confirm(user, receipt, null, body(user, version(receipt), "14.78", "14.78"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        UUID expense = UUID.fromString(json(result).get("expenseId").asString());
        assertThat(jdbc.sql("SELECT amount_minor FROM expense.expense WHERE id = :id").param("id", expense)
                .query(Long.class).single()).isEqualTo(1478L);
        assertThat(jdbc.sql("SELECT acknowledged_total_minor FROM receipt.receipt_confirmation WHERE receipt_id = "
                + ":id").param("id", receipt).query(Long.class).single()).isEqualTo(1478L);
        assertThat(jdbc.sql("SELECT total_minor FROM receipt.receipt_draft WHERE receipt_id = :id")
                .param("id", receipt).query(Long.class).single()).as("the draft is frozen evidence").isEqualTo(1378L);
        assertThat(jdbc.sql("SELECT count(*) FROM receipt.receipt_extraction WHERE receipt_id = :id AND raw_output "
                + "IS NOT NULL").param("id", receipt).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT idempotency_key FROM receipt.receipt_confirmation WHERE receipt_id = :id")
                .param("id", receipt).query(String.class).single()).startsWith("server-");
    }

    @Test
    void BR_RCP_10_the_total_and_date_must_be_explicitly_acknowledged() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = analysed(user);
        long version = version(receipt);

        assertThat(confirm(user, receipt, null, body(user, version, "13.78", "13.79")))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("RECEIPT_ACKNOWLEDGEMENT_MISMATCH");
        String wrongDate = body(user, version, "13.78", "13.78").replace("\"acknowledgedDate\":\"" + DATE,
                "\"acknowledgedDate\":\"" + DATE.minusDays(1));
        assertThat(confirm(user, receipt, null, wrongDate)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_ACKNOWLEDGEMENT_MISMATCH");
        String noAck = body(user, version, "13.78", "13.78").replaceAll(",\"acknowledgedTotal\":\\{[^}]*}", "");
        assertThat(confirm(user, receipt, null, noAck)).hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(expenseCount(household)).isZero();
        assertThat(state(user, receipt)).isEqualTo("READY_FOR_REVIEW");
    }

    @Test
    void BR_RCP_10_a_field_that_is_not_OK_must_be_resolved_before_confirming() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = analysed(user);
        // The parser could not decide between two readings of the total.
        jdbc.sql("UPDATE receipt.receipt_draft SET total_minor = NULL, fields = jsonb_set(jsonb_set(fields, "
                + "'{total,status}', '\"AMBIGUOUS\"'), '{total,candidates}', '[\"1.378\", \"1378.00\"]') "
                + "WHERE receipt_id = :id").param("id", receipt).update();
        long version = version(receipt);

        JsonNode review = review(user, receipt);
        assertThat(review.get("unresolvedFields")).extracting(JsonNode::asString).containsExactly("total");
        assertThat(absent(review, "proposedTotal")).as("an ambiguous total is never proposed").isTrue();
        assertThat(review.get("fields").get("total").get("candidates")).extracting(JsonNode::asString)
                .containsExactly("1.378", "1378.00");

        assertThat(confirm(user, receipt, null, body(user, version, "13.78", "13.78")))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("RECEIPT_FIELD_UNRESOLVED");
        assertThat(expenseCount(household)).isZero();

        String resolved = body(user, version, "13.78", "13.78").replace("{\"version\"",
                "{\"resolutions\":{\"total\":\"13.78\"},\"version\"");
        assertThat(confirm(user, receipt, null, resolved)).hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.sql("SELECT resolutions ->> 'total' FROM receipt.receipt_confirmation WHERE receipt_id = :id")
                .param("id", receipt).query(String.class).single()).isEqualTo("13.78");
        assertThat(confirm(user, receipt, null, resolved.replace("\"total\":\"13.78\"", "\"ownerUserId\":\"x\"")))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void BR_RCP_15_a_receipt_is_confirmed_exactly_once_and_a_retry_with_the_same_key_is_replayed() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = analysed(user);
        String body = body(user, version(receipt), "13.78", "13.78");

        MvcTestResult first = confirm(user, receipt, "retry-key", body);
        MvcTestResult replay = confirm(user, receipt, "retry-key", body);
        MvcTestResult again = confirm(user, receipt, "other-key", body);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(replay).hasStatus(HttpStatus.CREATED);
        assertThat(json(replay).get("expenseId").asString()).isEqualTo(json(first).get("expenseId").asString());
        assertThat(again).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code")
                .isEqualTo("RECEIPT_ALREADY_CONFIRMED");
        assertThat(confirm(user, receipt, "retry-key", body(user, version(receipt), "20.00", "20.00")))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson().extractingPath("$.code")
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(expenseCount(household)).isEqualTo(1L);
    }

    @Test
    void BR_RCP_15_parallel_confirmations_create_exactly_one_expense() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = analysed(user);
        String body = body(user, version(receipt), "13.78", "13.78");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<MvcTestResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "parallel-" + i;
                tasks.add(() -> confirm(user, receipt, key, body));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcTestResult> future : pool.invokeAll(tasks)) {
                MvcTestResult result = future.get();
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() != HttpStatus.CREATED.value()) {
                    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RECEIPT_ALREADY_CONFIRMED");
                }
            }
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(expenseCount(household)).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM expense.expense WHERE receipt_id = :id").param("id", receipt)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM receipt.receipt_confirmation WHERE receipt_id = :id")
                .param("id", receipt).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void a_receipt_changed_since_it_was_displayed_must_be_reviewed_again() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = analysed(user);

        assertThat(confirm(user, receipt, null, body(user, version(receipt) - 1, "13.78", "13.78")))
                .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code")
                .isEqualTo("RECEIPT_VERSION_CONFLICT");
        assertThat(expenseCount(household)).isZero();
    }

    @Test
    void an_expense_refused_by_the_ledger_leaves_the_receipt_open() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = analysed(user);
        long version = version(receipt);

        String unknownCategory = body(user, version, "13.78", "13.78").replace(GROCERIES,
                UUID.randomUUID().toString());
        assertThat(confirm(user, receipt, "refused", unknownCategory)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("CATEGORY_NOT_FOUND");
        String future = body(user, version, "13.78", "13.78").replace(DATE.toString(),
                LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
        assertThat(confirm(user, receipt, null, future)).hasStatus(HttpStatus.BAD_REQUEST);
        String notAMember = body(UUID.randomUUID(), version, "13.78", "13.78");
        assertThat(confirm(user, receipt, null, notAMember).getResponse().getStatus()).isBetween(400, 422);

        assertThat(expenseCount(household)).isZero();
        assertThat(state(user, receipt)).isEqualTo("READY_FOR_REVIEW");
        assertThat(version(receipt)).as("nothing of the receipt changed").isEqualTo(version);
        assertThat(jdbc.sql("SELECT count(*) FROM receipt.receipt_confirmation WHERE receipt_id = :id")
                .param("id", receipt).query(Long.class).single()).isZero();
        // The same key can be used again once the request is corrected.
        assertThat(confirm(user, receipt, "refused-2", body(user, version, "13.78", "13.78")))
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void a_receipt_can_be_confirmed_manually_without_any_analysis() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID receipt = upload(user, ReceiptFiles.jpeg(120, 200));
        int callsBefore = provider.calls();

        assertThat(confirm(user, receipt, null, body(user, version(receipt), "9.99", "9.99")))
                .hasStatus(HttpStatus.CREATED);

        assertThat(expenseCount(household)).isEqualTo(1L);
        assertThat(provider.calls()).as("nothing was sent to the AI provider").isEqualTo(callsBefore);
        assertThat(jdbc.sql("SELECT ai_processed FROM receipt.receipt WHERE id = :id").param("id", receipt)
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void confirming_while_the_analysis_is_queued_cancels_it() {
        UUID user = users.active();
        createHousehold(user);
        consent(user);
        UUID receipt = upload(user, ReceiptFiles.jpeg(120, 200));
        assertThat(state(user, receipt)).isEqualTo("QUEUED");
        int callsBefore = provider.calls();

        assertThat(confirm(user, receipt, null, body(user, version(receipt), "9.99", "9.99")))
                .hasStatus(HttpStatus.CREATED);
        worker.runOnce();

        assertThat(jdbc.sql("SELECT status FROM receipt.receipt_analysis_job WHERE receipt_id = :id")
                .param("id", receipt).query(String.class).list()).containsExactly("CANCELLED");
        assertThat(jdbc.sql("SELECT analysis_status FROM receipt.receipt WHERE id = :id").param("id", receipt)
                .query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(provider.calls()).isEqualTo(callsBefore);
    }

    // ------------------------------------------------------------------ duplicates (BR-RCP-14)

    @Test
    void BR_RCP_14_the_same_file_uploaded_twice_is_a_warning_never_a_block() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        byte[] file = ReceiptFiles.jpeg(140, 220);
        UUID first = upload(user, file);
        UUID second = upload(user, file);

        JsonNode duplicates = review(user, second).get("duplicates");

        assertThat(duplicates).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.get("reason").asString()).isEqualTo("SAME_FILE");
            assertThat(duplicate.get("receiptId").asString()).isEqualTo(first.toString());
            assertThat(duplicate.get("attachable").asBoolean()).isFalse();
        });
        assertThat(confirm(user, second, null, body(user, version(second), "5.00", "5.00")))
                .as("a duplicate is never silently discarded or blocked").hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.sql("SELECT count(*) FROM receipt.receipt WHERE household_id = :h").param("h", household)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void BR_RCP_14_a_re_photographed_receipt_is_a_similar_image() {
        UUID user = users.active();
        createHousehold(user);
        UUID first = upload(user, ReceiptFiles.jpeg(300, 500));
        UUID second = upload(user, ReceiptFiles.png(300, 500));

        JsonNode duplicates = review(user, second).get("duplicates");

        assertThat(duplicates).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.get("reason").asString()).isEqualTo("SIMILAR_IMAGE");
            assertThat(duplicate.get("receiptId").asString()).isEqualTo(first.toString());
        });
    }

    @Test
    void BR_RCP_16_the_partners_draft_and_personal_expense_never_appear_as_duplicates() {
        UUID owner = users.active();
        UUID household = createHousehold(owner);
        UUID partner = users.active();
        addMember(household, partner);
        byte[] file = ReceiptFiles.jpeg(160, 240);
        upload(partner, file);
        createExpense(partner, "PERSONAL", "13.78", "Carrefour");
        consent(owner);
        UUID receipt = upload(owner, file);
        worker.runOnce();
        alignDraftDate(receipt);

        JsonNode review = review(owner, receipt);

        assertThat(review.get("duplicates")).as("nothing of the partner is disclosed").isEmpty();
    }

    @Test
    void BR_RCP_14_a_matching_expense_is_proposed_and_the_receipt_can_be_attached_to_it() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID expense = createExpense(user, "SHARED", "13.78", "CARREFOUR");
        createExpense(user, "SHARED", "13.78", "Monoprix");
        createExpense(user, "SHARED", "13.79", "Carrefour");
        UUID receipt = analysed(user);
        alignDraftDate(receipt);

        JsonNode duplicates = review(user, receipt).get("duplicates");
        assertThat(duplicates).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.get("reason").asString()).isEqualTo("MATCHING_EXPENSE");
            assertThat(duplicate.get("expenseId").asString()).isEqualTo(expense.toString());
            assertThat(duplicate.get("attachable").asBoolean()).isTrue();
        });

        long reviewed = version(receipt);
        MvcTestResult attached = attach(user, receipt, expense, reviewed, "attach-1");

        assertThat(attached).hasStatus(HttpStatus.OK);
        assertThat(json(attached).get("state").asString()).isEqualTo("ATTACHED");
        assertThat(json(attach(user, receipt, expense, reviewed, "attach-1")).get("expenseId").asString())
                .as("same key: replayed").isEqualTo(expense.toString());
        Map<String, Object> row = jdbc.sql("SELECT amount_minor, source, receipt_id, merchant_display FROM "
                + "expense.expense WHERE id = :id").param("id", expense).query().singleRow();
        assertThat(row.get("receipt_id")).isEqualTo(receipt);
        assertThat(row.get("amount_minor")).as("attaching changes nothing of the expense").isEqualTo(1378L);
        assertThat(row.get("source")).isEqualTo("MANUAL");
        assertThat(row.get("merchant_display")).isEqualTo("CARREFOUR");
        assertThat(jdbc.sql("SELECT mode FROM receipt.receipt_confirmation WHERE receipt_id = :id")
                .param("id", receipt).query(String.class).single()).isEqualTo("ATTACH");
        assertThat(expenseCount(household)).as("no new expense").isEqualTo(3L);
        assertThat(confirm(user, receipt, null, body(user, version(receipt), "13.78", "13.78")))
                .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code")
                .isEqualTo("RECEIPT_ALREADY_CONFIRMED");
    }

    @Test
    void a_receipt_cannot_be_attached_to_an_unreachable_or_already_documented_expense() {
        UUID owner = users.active();
        UUID household = createHousehold(owner);
        UUID partner = users.active();
        addMember(household, partner);
        UUID partnerPersonal = createExpense(partner, "PERSONAL", "13.78", "Carrefour");
        UUID shared = createExpense(owner, "SHARED", "13.78", "Carrefour");
        UUID first = upload(owner, ReceiptFiles.jpeg(100, 150));
        UUID second = upload(owner, ReceiptFiles.jpeg(110, 170));
        assertThat(attach(owner, first, shared, version(first), null)).hasStatus(HttpStatus.OK);

        assertThat(attach(owner, second, partnerPersonal, version(second), null)).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(attach(owner, second, shared, version(second), null)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("EXPENSE_RECEIPT_ALREADY_LINKED");
        assertThat(attach(owner, second, UUID.randomUUID(), version(second), null))
                .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(state(owner, second)).as("a refused attachment leaves the receipt open").isEqualTo("UPLOADED");
        assertThat(jdbc.sql("SELECT count(*) FROM expense.expense WHERE id = :id AND receipt_id IS NULL")
                .param("id", partnerPersonal).query(Long.class).single()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ visibility (BR-RCP-16)

    @Test
    void BR_RCP_16_a_confirmed_receipt_follows_the_visibility_of_its_expense() {
        UUID owner = users.active();
        UUID household = createHousehold(owner);
        UUID partner = users.active();
        addMember(household, partner);
        UUID stranger = users.active();
        createHousehold(stranger);
        UUID shared = upload(owner, ReceiptFiles.jpeg(100, 150));
        UUID personal = upload(owner, ReceiptFiles.jpeg(105, 160));
        assertThat(receiptStatus(partner, shared)).as("a draft is private").isEqualTo(404);

        assertThat(confirm(owner, shared, null, body(owner, version(shared), "10.00", "10.00")))
                .hasStatus(HttpStatus.CREATED);
        assertThat(confirm(owner, personal, null, body(owner, version(personal), "7.00", "7.00")
                .replace("\"paidByUserId\"", "\"sharingType\":\"PERSONAL\",\"paidByUserId\"")))
                .hasStatus(HttpStatus.CREATED);

        assertThat(receiptStatus(partner, shared)).as("SHARED expense: the partner sees its receipt").isEqualTo(200);
        assertThat(mvc.get().uri("/api/v1/receipts/" + shared + "/pages/1/url")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(partner)).exchange()).hasStatus(HttpStatus.OK);
        assertThat(receiptStatus(partner, personal)).as("PERSONAL expense: never").isEqualTo(404);
        assertThat(mvc.get().uri("/api/v1/receipts/" + personal + "/pages/1/url")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(partner)).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(listed(partner)).containsExactly(shared.toString());
        assertThat(listed(owner)).containsExactlyInAnyOrder(shared.toString(), personal.toString());
        assertThat(receiptStatus(stranger, shared)).isEqualTo(404);
        assertThat(listed(stranger)).isEmpty();

        // Linked evidence is not deleted from the vault (BR-RCP-20), and not reviewable or confirmable any more.
        assertThat(mvc.delete().uri("/api/v1/receipts/" + shared)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(owner)).exchange()).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("RECEIPT_NOT_DELETABLE");
        assertThat(mvc.get().uri("/api/v1/receipts/" + shared + "/review")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(owner)).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void only_the_uploader_reviews_confirms_or_attaches_an_open_receipt() {
        UUID owner = users.active();
        UUID household = createHousehold(owner);
        UUID partner = users.active();
        addMember(household, partner);
        UUID stranger = users.active();
        createHousehold(stranger);
        UUID shared = createExpense(owner, "SHARED", "10.00", "Shop");
        UUID receipt = analysed(owner);
        long version = version(receipt);

        for (UUID other : List.of(partner, stranger)) {
            assertThat(mvc.get().uri("/api/v1/receipts/" + receipt + "/review")
                    .header(HttpHeaders.AUTHORIZATION, tokens.bearer(other)).exchange())
                    .hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                    .isEqualTo("RECEIPT_NOT_FOUND");
            assertThat(confirm(other, receipt, null, body(other, version, "13.78", "13.78")))
                    .hasStatus(HttpStatus.NOT_FOUND);
            assertThat(attach(other, receipt, shared, version, null)).hasStatus(HttpStatus.NOT_FOUND);
        }
        assertThat(mvc.get().uri("/api/v1/receipts/" + receipt + "/review").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/receipts/" + receipt + "/confirm")
                .contentType(MediaType.APPLICATION_JSON).content(body(owner, version, "13.78", "13.78")).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(state(owner, receipt)).isEqualTo("READY_FOR_REVIEW");
        assertThat(expenseCount(household)).isEqualTo(1L);
    }

    @Test
    void BR_HH_10_nothing_is_confirmed_on_a_dissolved_household() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID expense = createExpense(user, "SHARED", "10.00", "Shop");
        UUID receipt = upload(user, ReceiptFiles.jpeg(100, 150));
        long version = version(receipt);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();

        assertThat(confirm(user, receipt, null, body(user, version, "5.00", "5.00")))
                .hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(attach(user, receipt, expense, version, null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(expenseCount(household)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ helpers

    /** A receipt of {@code user} analysed by the fake provider (Carrefour, 13,78 EUR, 04/10/2026). */
    private UUID analysed(UUID user) {
        consent(user);
        UUID receipt = upload(user, ReceiptFiles.jpeg(120 + (int) (Math.random() * 400), 200));
        worker.runOnce();
        assertThat(state(user, receipt)).isEqualTo("READY_FOR_REVIEW");
        return receipt;
    }

    private void alignDraftDate(UUID receipt) {
        jdbc.sql("UPDATE receipt.receipt_draft SET purchase_date = :date WHERE receipt_id = :id").param("date", DATE)
                .param("id", receipt).update();
    }

    private static String body(UUID paidBy, long version, String amount, String acknowledged) {
        return "{\"version\":" + version + ",\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},"
                + "\"date\":\"" + DATE + "\",\"categoryId\":\"" + GROCERIES + "\",\"paidByUserId\":\"" + paidBy
                + "\",\"merchant\":\"Carrefour\",\"acknowledgedTotal\":{\"amount\":\"" + acknowledged
                + "\",\"currency\":\"EUR\"},\"acknowledgedDate\":\"" + DATE + "\"}";
    }

    private MvcTestResult confirm(UUID user, UUID receipt, String key, String body) {
        var request = mvc.post().uri("/api/v1/receipts/" + receipt + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.exchange();
    }

    private MvcTestResult attach(UUID user, UUID receipt, UUID expense, long version, String key) {
        var request = mvc.post().uri("/api/v1/receipts/" + receipt + "/attach")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expenseId\":\"" + expense + "\",\"version\":" + version + "}");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.exchange();
    }

    private JsonNode review(UUID user, UUID receipt) {
        MvcTestResult result = mvc.get().uri("/api/v1/receipts/" + receipt + "/review")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return json(result);
    }

    private UUID upload(UUID user, byte[] file) {
        MvcTestResult result = mvc.post().uri("/api/v1/receipts").multipart()
                .file(new MockMultipartFile("files", "receipt", "application/octet-stream", file))
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private void consent(UUID user) {
        assertThat(mvc.put().uri("/api/v1/me/ai-consents/RECEIPT_ANALYSIS")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"granted\":true,\"consentTextVersion\":\"receipt-analysis-v1\"}").exchange())
                .hasStatus(HttpStatus.OK);
    }

    private UUID createExpense(UUID user, String sharing, String amount, String merchant) {
        String money = "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}";
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + money + ",\"date\":\"" + DATE + "\",\"paidByUserId\":\"" + user
                        + "\",\"sharingType\":\"" + sharing + "\",\"merchant\":\"" + merchant
                        + "\",\"items\":[{\"categoryId\":\"" + GROCERIES + "\",\"amount\":" + money + "}]}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private String state(UUID user, UUID receipt) {
        return json(mvc.get().uri("/api/v1/receipts/" + receipt)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange()).get("state").asString();
    }

    private int receiptStatus(UUID user, UUID receipt) {
        return mvc.get().uri("/api/v1/receipts/" + receipt).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange().getResponse().getStatus();
    }

    private List<String> listed(UUID user) {
        List<String> ids = new ArrayList<>();
        json(mvc.get().uri("/api/v1/receipts?limit=100").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange()).get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private List<String> usableCategories(UUID user) {
        List<String> ids = new ArrayList<>();
        json(mvc.get().uri("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange())
                .get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private long version(UUID receipt) {
        return jdbc.sql("SELECT version FROM receipt.receipt WHERE id = :id").param("id", receipt).query(Long.class)
                .single();
    }

    private long expenseCount(UUID household) {
        return jdbc.sql("SELECT count(*) FROM expense.expense WHERE household_id = :h").param("h", household)
                .query(Long.class).single();
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", user).update();
    }

    private static boolean absent(JsonNode node, String field) {
        return !node.has(field) || node.get(field).isNull();
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
