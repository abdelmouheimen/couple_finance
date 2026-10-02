package com.couplefinance.expense.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.expense.api.ExpenseCreated;
import com.couplefinance.expense.application.ExpenseCreatedProbeListener;
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

/** {@code POST /api/v1/expenses} - Issue #15 (BR-EXP-01, 02, 05..08, 10, 13, 14, BR-MON-02/03/07, BR-HH-10). */
@IntegrationTest
class ExpenseIntegrationTest {

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
    ExpenseCreatedProbeListener listener;

    // ------------------------------------------------------------------ creation

    @Test
    void BR_EXP_01_creates_a_shared_expense_with_items_etag_audit_and_event() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        MvcTestResult result = post(user, null, body("12.50", today(), user, "SHARED",
                "[" + item(GROCERIES, "10.00", "Fruit") + "," + item(HOUSING, "2.50", null) + "]",
                "Carrefour Market", "weekly shop"));

        assertThat(result).hasStatus(HttpStatus.CREATED).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        JsonNode json = json(result);
        assertThat(json.get("kind").asString()).isEqualTo("EXPENSE");
        assertThat(json.get("amount").get("amount").asString()).isEqualTo("12.50");
        assertThat(json.get("amount").get("currency").asString()).isEqualTo("EUR");
        assertThat(json.get("sharingType").asString()).isEqualTo("SHARED");
        assertThat(json.get("merchant").asString()).isEqualTo("Carrefour Market");
        assertThat(json.get("createdBy").asString()).isEqualTo(user.toString());
        assertThat(json.get("source").asString()).isEqualTo("MANUAL");
        assertThat(json.get("items")).hasSize(2);
        assertThat(json.get("items").get(0).get("position").asInt()).isEqualTo(1);
        assertThat(json.get("items").get(0).get("amount").get("amount").asString()).isEqualTo("10.00");
        assertThat(json.get("items").get(1).get("label").isNull()).isTrue();

        UUID id = UUID.fromString(json.get("id").asString());
        Map<String, Object> row = jdbc.sql("SELECT * FROM expense.expense WHERE id = :id").param("id", id)
                .query().singleRow();
        assertThat(row.get("household_id")).isEqualTo(household);
        assertThat(row.get("amount_minor")).isEqualTo(1250L);
        assertThat(row.get("currency")).isEqualTo("EUR");
        assertThat(row.get("owner_user_id")).isNull();
        assertThat(row.get("paid_by_user_id")).isEqualTo(user);
        assertThat(row.get("created_by")).isEqualTo(user);
        assertThat(row.get("merchant_display")).isEqualTo("Carrefour Market");
        assertThat(row.get("merchant_key")).isNotNull();
        assertThat(jdbc.sql("SELECT sum(amount_minor) FROM expense.expense_item WHERE expense_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1250L);
        assertThat(jdbc.sql("SELECT count(*) FROM expense.audit_event WHERE entity_id = :id AND action = 'CREATE' "
                + "AND actor_user_id = :user AND owner_user_id IS NULL AND household_id = :household")
                .param("id", id).param("user", user).param("household", household).query(Long.class).single())
                .isEqualTo(1L);
        assertThat(eventsFor(id)).singleElement().satisfies(event -> {
            assertThat(event.householdId()).isEqualTo(household);
            assertThat(event.ownerUserId()).isNull();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM modulith.event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + id + "%").query(Long.class).single())
                .as("one publication per transactional listener: the test probe and merchant-rule learning")
                .isEqualTo(2L);
    }

    @Test
    void BR_EXP_07_personal_expense_is_owned_by_its_creator_and_audited_as_personal() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        JsonNode json = json(post(user, null, body("5.00", today(), user, "PERSONAL",
                "[" + item(GROCERIES, "5.00", null) + "]", null, null)));

        UUID id = UUID.fromString(json.get("id").asString());
        assertThat(json.get("sharingType").asString()).isEqualTo("PERSONAL");
        assertThat(jdbc.sql("SELECT owner_user_id FROM expense.expense WHERE id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(user);
        assertThat(jdbc.sql("SELECT owner_user_id FROM expense.audit_event WHERE entity_id = :id")
                .param("id", id).query(UUID.class).single()).isEqualTo(user);
        assertThat(eventsFor(id)).singleElement().satisfies(event -> {
            assertThat(event.ownerUserId()).isEqualTo(user);
            assertThat(event.householdId()).isEqualTo(household);
        });
    }

    @Test
    void BR_EXP_07_a_partner_can_be_the_payer_of_a_shared_expense() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);

        assertThat(post(user, null, body("8.00", today(), partner, "SHARED",
                "[" + item(GROCERIES, "8.00", null) + "]", null, null))).hasStatus(HttpStatus.CREATED);
        // the partner is a full member and may create too
        assertThat(post(partner, null, body("3.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "3.00", null) + "]", null, null))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void BR_EXP_07_a_personal_expense_cannot_be_paid_by_the_partner_and_the_error_leaks_nothing() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);

        MvcTestResult result = post(user, null, body("5.00", today(), partner, "PERSONAL",
                "[" + item(GROCERIES, "5.00", null) + "]", "Secret shop", "private note"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_PERSONAL_PAYER_MISMATCH");
        assertThat(text(result)).doesNotContain("Secret shop")
                .doesNotContain("private note");
        assertThat(expenseCount(household)).isZero();
    }

    @Test
    void BR_EXP_07_paid_by_must_be_an_active_member_of_the_household() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID stranger = users.active();
        createHousehold(stranger);

        assertThat(post(user, null, body("5.00", today(), stranger, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_PAID_BY_INVALID");
        assertThat(post(user, null, body("5.00", today(), UUID.randomUUID(), "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_PAID_BY_INVALID");
        assertThat(expenseCount(household)).isZero();
    }

    @Test
    void BR_EXP_07_the_default_sharing_type_is_shared() {
        UUID user = users.active();
        createHousehold(user);
        String json = "{\"amount\":{\"amount\":\"4.00\",\"currency\":\"EUR\"},\"date\":\"" + today()
                + "\",\"paidByUserId\":\"" + user + "\",\"items\":[" + item(GROCERIES, "4.00", null) + "]}";

        MvcTestResult result = mvc.post().uri(URI).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(json(result).get("sharingType").asString()).isEqualTo("SHARED");
    }

    // ------------------------------------------------------------------ money and items

    @Test
    void BR_EXP_08_items_must_sum_to_the_amount() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        assertThat(post(user, null, body("12.50", today(), user, "SHARED",
                "[" + item(GROCERIES, "10.00", null) + "," + item(HOUSING, "2.49", null) + "]", null, null)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_ITEMS_SUM_MISMATCH");
        assertThat(expenseCount(household)).isZero();
    }

    @Test
    void BR_EXP_08_at_most_one_item_per_category_and_one_to_ten_items() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(post(user, null, body("10.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "," + item(GROCERIES, "5.00", null) + "]", null, null)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_ITEM_DUPLICATE_CATEGORY");
        assertThat(post(user, null, body("10.00", today(), user, "SHARED", "[]", null, null)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_ITEM_COUNT_INVALID");
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(item(UUID.randomUUID().toString(), "1.00", null));
        }
        assertThat(post(user, null, body("11.00", today(), user, "SHARED", "[" + String.join(",", eleven) + "]",
                null, null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void BR_MON_03_excess_decimals_are_rejected_not_rounded() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        assertThat(post(user, null, body("12.505", today(), user, "SHARED",
                "[" + item(GROCERIES, "12.505", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("TOO_MANY_DECIMALS");
        assertThat(post(user, null, body("1e2", today(), user, "SHARED",
                "[" + item(GROCERIES, "1.00", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_AMOUNT_FORMAT");
        assertThat(expenseCount(household)).isZero();
    }

    @Test
    void BR_MON_07_amount_must_be_positive_and_within_the_currency_maximum() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(post(user, null, body("0.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "0.00", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(post(user, null, body("-1.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "-1.00", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("AMOUNT_NOT_POSITIVE");
        // EUR maximum is 1,000,000.00
        assertThat(post(user, null, body("1000000.01", today(), user, "SHARED",
                "[" + item(GROCERIES, "1000000.01", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("AMOUNT_EXCEEDS_MAXIMUM");
        assertThat(post(user, null, body("1000000.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "1000000.00", null) + "]", null, null))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void BR_EXP_05_currency_must_equal_the_household_currency() {
        UUID user = users.active();
        createHousehold(user);
        String usd = "{\"amount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"date\":\"" + today()
                + "\",\"paidByUserId\":\"" + user + "\",\"items\":[{\"categoryId\":\"" + GROCERIES
                + "\",\"amount\":{\"amount\":\"5.00\",\"currency\":\"USD\"}}]}";

        assertThat(mvc.post().uri(URI).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(usd).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code").isEqualTo("CURRENCY_MISMATCH");
    }

    // ------------------------------------------------------------------ date

    @Test
    void BR_EXP_06_date_window_is_enforced_in_the_household_timezone() {
        UUID user = users.active();
        createHousehold(user);
        LocalDate today = today();
        String items = "[" + item(GROCERIES, "5.00", null) + "]";

        assertThat(post(user, null, body("5.00", today.plusDays(1), user, "SHARED", items, null, null)))
                .hasStatus(HttpStatus.CREATED);
        assertThat(post(user, null, body("5.00", today.minusYears(5), user, "SHARED", items, null, null)))
                .hasStatus(HttpStatus.CREATED);
        assertThat(post(user, null, body("5.00", today.plusDays(2), user, "SHARED", items, null, null)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_DATE_OUT_OF_RANGE");
        assertThat(post(user, null, body("5.00", today.minusYears(5).minusDays(1), user, "SHARED", items, null,
                null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_DATE_OUT_OF_RANGE");
    }

    // ------------------------------------------------------------------ categories

    @Test
    void BR_EXP_14_a_category_of_another_household_is_404() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID other = users.active();
        createHousehold(other);
        String foreign = createCategory(other, "Foreign");

        MvcTestResult result = post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(foreign, "5.00", null) + "]", null, null));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("CATEGORY_NOT_FOUND");
        assertThat(text(result)).doesNotContain("Foreign");
        assertThat(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(UUID.randomUUID().toString(), "5.00", null) + "]", null, null)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(expenseCount(household)).isZero();
    }

    @Test
    void BR_EXP_14_an_archived_category_is_rejected_but_own_and_system_categories_are_accepted() {
        UUID user = users.active();
        createHousehold(user);
        String own = createCategory(user, "Pets");
        assertThat(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(own, "2.00", null) + "," + item(GROCERIES, "3.00", null) + "]", null, null)))
                .hasStatus(HttpStatus.CREATED);

        assertThat(mvc.patch().uri("/api/v1/categories/" + own).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\": true}").exchange()).hasStatus(HttpStatus.OK);

        assertThat(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(own, "5.00", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_CATEGORY_ARCHIVED");
    }

    // ------------------------------------------------------------------ merchant and mass assignment

    @Test
    void a_merchant_is_stored_as_given_and_a_meaningless_one_is_rejected() {
        UUID user = users.active();
        createHousehold(user);

        JsonNode json = json(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", "  CARREFOUR city #123 ", null)));

        assertThat(json.get("merchant").asString()).isEqualTo("  CARREFOUR city #123 ");
        assertThat(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", "***", null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_MERCHANT_INVALID");
    }

    @Test
    void household_and_creator_cannot_be_supplied_by_the_client() {
        UUID user = users.active();
        createHousehold(user);
        String json = "{\"householdId\":\"" + UUID.randomUUID() + "\",\"createdBy\":\"" + UUID.randomUUID()
                + "\",\"amount\":{\"amount\":\"4.00\",\"currency\":\"EUR\"},\"date\":\"" + today()
                + "\",\"paidByUserId\":\"" + user + "\",\"items\":[" + item(GROCERIES, "4.00", null) + "]}";

        assertThat(mvc.post().uri(URI).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void note_longer_than_500_characters_is_rejected() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", null, "x".repeat(501))))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.post().uri(URI).contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_user_without_household_gets_404() {
        UUID user = users.active();

        assertThat(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", null, null))).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
    }

    @Test
    void BR_HH_10_writes_on_a_dissolved_household_are_rejected() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();

        assertThat(post(user, null, body("5.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", null, null))).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(expenseCount(household)).isZero();
    }

    // ------------------------------------------------------------------ idempotency (BR-EXP-13)

    @Test
    void BR_EXP_13_same_key_and_same_request_returns_the_original_expense_once() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String key = UUID.randomUUID().toString();
        String request = body("9.99", today(), user, "SHARED", "[" + item(GROCERIES, "9.99", null) + "]", "Shop",
                null);

        MvcTestResult first = post(user, key, request);
        MvcTestResult second = post(user, key, request);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.CREATED).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(json(second)).isEqualTo(json(first));
        assertThat(expenseCount(household)).isEqualTo(1L);
    }

    @Test
    void BR_EXP_13_same_key_with_a_different_request_is_422() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String key = UUID.randomUUID().toString();
        post(user, key, body("9.99", today(), user, "SHARED", "[" + item(GROCERIES, "9.99", null) + "]", null,
                null));

        assertThat(post(user, key, body("9.98", today(), user, "SHARED", "[" + item(GROCERIES, "9.98", null) + "]",
                null, null))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson().extractingPath("$.code")
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(expenseCount(household)).isEqualTo(1L);
    }

    @Test
    void BR_EXP_13_a_failed_request_releases_the_key_so_that_a_corrected_retry_succeeds() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String key = UUID.randomUUID().toString();

        assertThat(post(user, key, body("9.99", today(), user, "SHARED", "[" + item(GROCERIES, "9.00", null) + "]",
                null, null))).hasStatus(HttpStatus.BAD_REQUEST);
        // not a replay of the failure: the corrected request is different, but the key is free again
        assertThat(post(user, key, body("9.99", today(), user, "SHARED", "[" + item(GROCERIES, "9.99", null) + "]",
                null, null))).hasStatus(HttpStatus.CREATED);
        assertThat(expenseCount(household)).isEqualTo(1L);
    }

    @Test
    void BR_EXP_13_simultaneous_requests_with_the_same_key_create_exactly_one_expense() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String key = UUID.randomUUID().toString();
        String request = body("7.00", today(), user, "SHARED", "[" + item(GROCERIES, "7.00", null) + "]", null, null);
        int contenders = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return post(user, key, request).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).contains(201).allMatch(status -> status == 201 || status == 409);
            assertThat(expenseCount(household)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void BR_EXP_13_another_user_with_the_same_key_creates_an_independent_expense() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        String key = UUID.randomUUID().toString();

        MvcTestResult personal = post(user, key, body("6.00", today(), user, "PERSONAL",
                "[" + item(GROCERIES, "6.00", "secret label") + "]", "Private shop", null));
        MvcTestResult partnerResult = post(partner, key, body("6.00", today(), partner, "SHARED",
                "[" + item(GROCERIES, "6.00", null) + "]", null, null));

        assertThat(personal).hasStatus(HttpStatus.CREATED);
        assertThat(partnerResult).hasStatus(HttpStatus.CREATED);
        assertThat(json(partnerResult).get("id").asString()).isNotEqualTo(json(personal).get("id").asString());
        assertThat(text(partnerResult)).doesNotContain("Private shop")
                .doesNotContain("secret label");
        assertThat(expenseCount(household)).isEqualTo(2L);
    }

    @Test
    void a_malformed_idempotency_key_is_400() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(post(user, "bad key with spaces", body("5.00", today(), user, "SHARED",
                "[" + item(GROCERIES, "5.00", null) + "]", null, null))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    // ------------------------------------------------------------------ helpers

    private static LocalDate today() {
        return LocalDate.now(PARIS);
    }

    private static String item(String category, String amount, String label) {
        return "{\"categoryId\":\"" + category + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}" + (label == null ? "" : ",\"label\":\"" + label + "\"") + "}";
    }

    private static String body(String amount, LocalDate date, UUID paidBy, String sharing, String items,
            String merchant, String note) {
        return "{\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},\"date\":\"" + date
                + "\",\"paidByUserId\":\"" + paidBy + "\",\"sharingType\":\"" + sharing + "\",\"items\":" + items
                + (merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"")
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    private MvcTestResult post(UUID user, String idempotencyKey, String body) {
        var request = mvc.post().uri(URI).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
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

    private String createCategory(UUID user, String name) {
        MvcTestResult result = mvc.post().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return json(result).get("id").asString();
    }

    /** Joining by invitation does not exist yet: seeds the second member directly. */
    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", user).update();
    }

    private long expenseCount(UUID household) {
        return jdbc.sql("SELECT count(*) FROM expense.expense WHERE household_id = :id").param("id", household)
                .query(Long.class).single();
    }

    private List<ExpenseCreated> eventsFor(UUID expenseId) {
        return listener.received().stream().filter(event -> event.expenseId().equals(expenseId)).toList();
    }
}
