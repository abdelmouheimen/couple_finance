package com.couplefinance.expense.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.expense.domain.ExpenseRepository;
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

/** {@code POST /api/v1/expenses} with {@code kind=REFUND} - Issue #19 (BR-EXP-02, BR-EXP-03, BR-EXP-08, BR-MON-06). */
@IntegrationTest
class RefundIntegrationTest {

    private static final String URI = "/api/v1/expenses";
    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    private static final String HOUSING = "019a0000-0000-7000-8000-000000000002";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

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
    ExpenseRepository expenses;

    // ------------------------------------------------------------------ happy paths

    @Test
    void BR_EXP_03_a_refund_links_to_its_original_and_items_default_proportionally() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00",
                "[" + item(GROCERIES, "7.00") + "," + item(HOUSING, "3.00") + "]");

        MvcTestResult result = refund(user, original, "0.05", today(), null, null);

        assertThat(result).hasStatus(HttpStatus.CREATED).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        JsonNode json = json(result);
        assertThat(json.get("kind").asString()).isEqualTo("REFUND");
        assertThat(json.get("refundOf").asString()).isEqualTo(original);
        assertThat(json.get("sharingType").asString()).isEqualTo("SHARED");
        assertThat(json.get("items")).hasSize(2);
        assertThat(json.get("items").get(0).get("categoryId").asString()).isEqualTo(GROCERIES);
        assertThat(json.get("items").get(0).get("amount").get("amount").asString()).isEqualTo("0.04");
        assertThat(json.get("items").get(1).get("amount").get("amount").asString()).isEqualTo("0.01");
        UUID id = UUID.fromString(json.get("id").asString());
        assertThat(jdbc.sql("SELECT refund_of_expense_id FROM expense.expense WHERE id = :id AND household_id = :h")
                .param("id", id).param("h", household).query(UUID.class).single()).isEqualTo(UUID.fromString(original));
        assertThat(jdbc.sql("SELECT count(*) FROM expense.audit_event WHERE entity_id = :id "
                + "AND changes -> 'refundOfExpenseId' ->> 'new' = :original").param("id", id)
                .param("original", original).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void BR_EXP_08_explicit_items_are_kept_when_they_sum_to_the_refund() {
        UUID user = users.active();
        createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00",
                "[" + item(GROCERIES, "7.00") + "," + item(HOUSING, "3.00") + "]");

        MvcTestResult result = refund(user, original, "2.00", today(), null, "[" + item(HOUSING, "2.00") + "]");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(json(result).get("items")).hasSize(1);
        assertThat(json(result).get("items").get(0).get("categoryId").asString()).isEqualTo(HOUSING);
    }

    @Test
    void BR_EXP_03_a_refund_counts_for_its_own_date_and_may_total_the_original_exactly() {
        UUID user = users.active();
        createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");

        assertThat(refund(user, original, "4.00", today(), null, null)).hasStatus(HttpStatus.CREATED);
        MvcTestResult second = refund(user, original, "6.00", today(), null, null);

        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(json(second).get("date").asString()).isEqualTo(today().toString());
        assertThat(refund(user, original, "0.01", today(), null, null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_REFUND_EXCEEDS_ORIGINAL");
    }

    @Test
    void BR_EXP_03_a_refund_without_original_is_an_independent_refund_that_needs_items() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(post(user, "{\"kind\":\"REFUND\",\"amount\":{\"amount\":\"3.00\",\"currency\":\"EUR\"},"
                + "\"date\":\"" + today() + "\",\"paidByUserId\":\"" + user + "\",\"items\":["
                + item(GROCERIES, "3.00") + "]}")).hasStatus(HttpStatus.CREATED).bodyJson()
                .extractingPath("$.kind").isEqualTo("REFUND");
        assertThat(post(user, "{\"kind\":\"REFUND\",\"amount\":{\"amount\":\"3.00\",\"currency\":\"EUR\"},"
                + "\"date\":\"" + today() + "\",\"paidByUserId\":\"" + user + "\"}")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_ITEM_COUNT_INVALID");
    }

    @Test
    void BR_EXP_03_the_live_refund_queries_ignore_deleted_refunds() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");
        UUID originalId = UUID.fromString(original);
        assertThat(expenses.hasLiveRefunds(originalId, household)).isFalse();
        assertThat(expenses.sumLiveRefundsMinor(originalId, household)).isZero();

        UUID refundId = UUID.fromString(json(refund(user, original, "4.00", today(), null, null)).get("id")
                .asString());
        assertThat(expenses.hasLiveRefunds(originalId, household)).isTrue();
        assertThat(expenses.sumLiveRefundsMinor(originalId, household)).isEqualTo(400L);

        jdbc.sql("UPDATE expense.expense SET deleted_at = now(), deleted_by = :user WHERE id = :id")
                .param("user", user).param("id", refundId).update();
        assertThat(expenses.hasLiveRefunds(originalId, household)).isFalse();
        assertThat(expenses.sumLiveRefundsMinor(originalId, household)).isZero();
        assertThat(expenses.sumLiveRefundsMinor(originalId, UUID.randomUUID())).isZero();
        assertThat(refund(user, original, "10.00", today(), null, null)).hasStatus(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------ rule violations

    @Test
    void BR_EXP_03_a_refund_dated_before_its_original_is_rejected() {
        UUID user = users.active();
        createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");

        assertThat(refund(user, original, "1.00", today().minusDays(1), null, null))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_REFUND_DATE_BEFORE_ORIGINAL");
    }

    @Test
    void BR_EXP_03_a_refund_with_another_sharing_type_than_its_original_is_rejected() {
        UUID user = users.active();
        createHousehold(user);
        String shared = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");
        String personal = createExpense(user, "PERSONAL", "10.00", "[" + item(GROCERIES, "10.00") + "]");

        assertThat(refund(user, shared, "1.00", today(), "PERSONAL", null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_REFUND_VISIBILITY_MISMATCH");
        assertThat(refund(user, personal, "1.00", today(), "SHARED", null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_REFUND_VISIBILITY_MISMATCH");
    }

    @Test
    void BR_EXP_03_a_refund_cannot_link_to_another_refund() {
        UUID user = users.active();
        createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");
        String first = json(refund(user, original, "4.00", today(), null, null)).get("id").asString();

        assertThat(refund(user, first, "1.00", today(), null, null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_REFUND_ORIGINAL_INVALID");
    }

    @Test
    void BR_EXP_03_refund_of_is_only_allowed_for_a_refund() {
        UUID user = users.active();
        createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");

        assertThat(post(user, "{\"amount\":{\"amount\":\"3.00\",\"currency\":\"EUR\"},\"date\":\"" + today()
                + "\",\"paidByUserId\":\"" + user + "\",\"refundOf\":\"" + original + "\",\"items\":["
                + item(GROCERIES, "3.00") + "]}")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("EXPENSE_REFUND_OF_NOT_ALLOWED");
    }

    @Test
    void a_transfer_cannot_be_created_through_the_expense_endpoint() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(post(user, "{\"kind\":\"TRANSFER\",\"amount\":{\"amount\":\"3.00\",\"currency\":\"EUR\"},"
                + "\"date\":\"" + today() + "\",\"paidByUserId\":\"" + user + "\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void BR_EXP_03_an_original_of_another_household_or_unknown_is_404() {
        UUID user = users.active();
        UUID stranger = users.active();
        createHousehold(user);
        createHousehold(stranger);
        String foreign = createExpense(stranger, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");

        MvcTestResult foreignResult = refund(user, foreign, "1.00", today(), null, null);
        MvcTestResult unknownResult = refund(user, UUID.randomUUID().toString(), "1.00", today(), null, null);

        assertThat(foreignResult).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(unknownResult).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(json(foreignResult).get("code").asString()).isEqualTo(json(unknownResult).get("code").asString());
        assertThat(json(foreignResult).get("title").asString())
                .isEqualTo(json(unknownResult).get("title").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM expense.expense WHERE refund_of_expense_id = :id")
                .param("id", UUID.fromString(foreign)).query(Long.class).single()).isZero();
    }

    @Test
    void BR_EXP_07_BR_EXP_03_a_partner_cannot_link_a_refund_to_a_personal_original() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        String personal = createExpense(owner, "PERSONAL", "10.00", "[" + item(GROCERIES, "10.00") + "]");

        MvcTestResult partnerAttempt = refund(partner, personal, "1.00", today(), null, null);
        MvcTestResult unknown = refund(partner, UUID.randomUUID().toString(), "1.00", today(), null, null);

        assertThat(partnerAttempt).hasStatus(HttpStatus.NOT_FOUND);
        // indistinguishable from an unknown original (no existence leak): same status and code, nothing about it
        assertThat(json(partnerAttempt).get("code").asString()).isEqualTo(json(unknown).get("code").asString());
        assertThat(text(partnerAttempt)).doesNotContain(personal);
        assertThat(refund(partner, personal, "1.00", today(), "PERSONAL", null)).hasStatus(HttpStatus.NOT_FOUND);

        MvcTestResult ownerRefund = refund(owner, personal, "1.00", today(), null, null);
        assertThat(ownerRefund).hasStatus(HttpStatus.CREATED);
        assertThat(json(ownerRefund).get("sharingType").asString()).isEqualTo("PERSONAL");
        UUID id = UUID.fromString(json(ownerRefund).get("id").asString());
        assertThat(jdbc.sql("SELECT owner_user_id FROM expense.expense WHERE id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(owner);
    }

    @Test
    void BR_EXP_03_a_partner_can_refund_a_shared_original() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        String shared = createExpense(owner, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");

        assertThat(refund(partner, shared, "2.00", today(), null, null)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void a_deleted_original_is_404() {
        UUID user = users.active();
        createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");
        jdbc.sql("UPDATE expense.expense SET deleted_at = now(), deleted_by = :user WHERE id = :id")
                .param("user", user).param("id", UUID.fromString(original)).update();

        assertThat(refund(user, original, "1.00", today(), null, null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_HH_10_a_refund_on_a_dissolved_household_is_rejected() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();

        assertThat(refund(user, original, "1.00", today(), null, null)).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
    }

    @Test
    void unauthenticated_refund_is_401() {
        assertThat(mvc.post().uri(URI).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"REFUND\"}").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void BR_EXP_03_two_concurrent_refunds_exceeding_the_original_exactly_one_succeeds() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String original = createExpense(user, "SHARED", "10.00", "[" + item(GROCERIES, "10.00") + "]");
        int contenders = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return refund(user, original, "6.00", today(), null, null).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(201, 400);
            assertThat(jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM expense.expense "
                    + "WHERE refund_of_expense_id = :id AND household_id = :h").param("id", UUID.fromString(original))
                    .param("h", household).query(Long.class).single()).isEqualTo(600L);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static LocalDate today() {
        return LocalDate.now(PARIS);
    }

    private static String item(String category, String amount) {
        return "{\"categoryId\":\"" + category + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}}";
    }

    private MvcTestResult refund(UUID user, String original, String amount, LocalDate date, String sharing,
            String items) {
        return post(user, "{\"kind\":\"REFUND\",\"refundOf\":\"" + original + "\",\"amount\":{\"amount\":\""
                + amount + "\",\"currency\":\"EUR\"},\"date\":\"" + date + "\",\"paidByUserId\":\"" + user + "\""
                + (sharing == null ? "" : ",\"sharingType\":\"" + sharing + "\"")
                + (items == null ? "" : ",\"items\":" + items) + "}");
    }

    private String createExpense(UUID user, String sharing, String amount, String items) {
        MvcTestResult result = post(user, "{\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},"
                + "\"date\":\"" + today() + "\",\"paidByUserId\":\"" + user + "\",\"sharingType\":\"" + sharing
                + "\",\"items\":" + items + "}");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return json(result).get("id").asString();
    }

    private MvcTestResult post(UUID user, String body) {
        return mvc.post().uri(URI).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(text(result));
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    /** Joining by invitation does not exist yet: seeds the second member directly. */
    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", user).update();
    }
}
