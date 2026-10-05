package com.couplefinance.budget.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.budget.application.BudgetEventsProbeListener;
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

/**
 * Category limits and the overrun warning - Issue #25 (BR-BUD-01, BR-BUD-02, BR-BUD-04, BR-BUD-07, BR-CAT-03,
 * BR-HH-03, BR-HH-10, BR-MON-02/03/07).
 */
@IntegrationTest
class BudgetCategoryLimitsIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";

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
    BudgetEventsProbeListener listener;

    @Test
    void BR_BUD_02_a_budget_may_consist_only_of_category_limits() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        MvcTestResult created = put(user, start, body(null, line(GROCERIES, "300.00")), null);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        JsonNode budget = json(created);
        assertThat(isAbsent(budget.get("overallLimit"))).isTrue();
        assertThat(budget.get("categoryLimits")).hasSize(1);
        assertThat(budget.get("categoryLimits").get(0).get("categoryId").asString()).isEqualTo(GROCERIES);
        assertThat(budget.get("categoryLimits").get(0).get("limit").get("amount").asString()).isEqualTo("300.00");
        assertThat(budget.get("categoryLimits").get(0).get("limit").get("currency").asString()).isEqualTo("EUR");
        assertThat(isAbsent(budget.get("categoryLimitsWarning"))).isTrue();
        JsonNode read = json(get(user, start));
        assertThat(read.get("categoryLimits")).hasSize(1);
        assertThat(isAbsent(read.get("overallConsumption"))).isTrue();
        assertThat(jdbc.sql("SELECT limit_minor FROM budget.budget_category WHERE household_id = :h")
                .param("h", household).query(Long.class).single()).isEqualTo(30_000L);
    }

    @Test
    void BR_BUD_02_a_budget_without_any_limit_is_400_and_stores_nothing() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        for (String body : new String[] {"{\"categoryLimits\":[]}", "{}", "{\"overallLimit\":null,\"categoryLimits\":[]}"}) {
            assertThat(putRaw(user, start, body, null)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                    .extractingPath("$.code").isEqualTo("BUDGET_LIMIT_REQUIRED");
        }
        assertThat(count(household)).isZero();
    }

    @Test
    void BR_BUD_02_the_last_limit_cannot_be_removed_from_an_existing_budget() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, body(null, line(GROCERIES, "300.00")), null);

        // dropping the overall limit while no category limit remains, and emptying a category-only budget
        assertThat(putRaw(user, start, "{}", "\"0\"")).hasStatus(HttpStatus.OK); // lines untouched: still valid
        assertThat(putRaw(user, start, "{\"categoryLimits\":[]}", "\"0\"")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("BUDGET_LIMIT_REQUIRED");
        assertThat(lineCount(household)).isEqualTo(1);

        put(user, firstPeriod(household), body("100.00"), "\"0\"");
        assertThat(putRaw(user, start, "{\"overallLimit\":null,\"categoryLimits\":[]}", "\"1\""))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(lineCount(household)).isEqualTo(1);
    }

    @Test
    void BR_BUD_04_sum_exceeding_overall_warns_but_does_not_block() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        String own = createCategory(user, "Pets");

        MvcTestResult created = put(user, start, body("100.00", line(GROCERIES, "60.00"), line(own, "50.00")), null);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        for (JsonNode budget : List.of(json(created), json(get(user, start)))) {
            JsonNode warning = budget.get("categoryLimitsWarning");
            assertThat(warning.get("categoryLimitsTotal").get("amount").asString()).isEqualTo("110.00");
            assertThat(warning.get("categoryLimitsTotal").get("currency").asString()).isEqualTo("EUR");
            assertThat(warning.get("overallLimit").get("amount").asString()).isEqualTo("100.00");
        }
        // computed on read: raising the overall limit clears it, nothing is stored
        JsonNode raised = json(put(user, start, body("110.00", line(GROCERIES, "60.00"), line(own, "50.00")),
                "\"0\""));
        assertThat(isAbsent(raised.get("categoryLimitsWarning"))).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema = 'budget'
                AND column_name ILIKE '%warning%'""").query(Integer.class).single()).isZero();
    }

    @Test
    void BR_BUD_04_limits_adding_up_to_the_overall_limit_do_not_warn() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        MvcTestResult created = put(user, firstPeriod(household), body("100.00", line(GROCERIES, "100.00")), null);

        assertThat(isAbsent(json(created).get("categoryLimitsWarning"))).isTrue();
    }

    @Test
    void BR_BUD_01_two_limits_for_one_category_are_400_and_store_nothing() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        assertThat(put(user, start, body("500.00", line(GROCERIES, "10.00"), line(GROCERIES, "20.00")), null))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("DUPLICATE_CATEGORY_LIMIT");
        assertThat(count(household)).isZero();
        assertThat(lineCount(household)).isZero();
    }

    @Test
    void BR_MON_02_03_07_invalid_category_limit_amounts_are_400() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        assertThat(put(user, start, body("100.00", line(GROCERIES, "0.00")), null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(put(user, start, body("100.00", line(GROCERIES, "-5.00")), null))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(put(user, start, body("100.00", line(GROCERIES, "1.001")), null))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("TOO_MANY_DECIMALS");
        assertThat(putRaw(user, start, "{\"categoryLimits\":[{\"categoryId\":\"" + GROCERIES
                + "\",\"limit\":{\"amount\":\"5.00\",\"currency\":\"USD\"}}]}", null))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("CURRENCY_MISMATCH");
        assertThat(putRaw(user, start, "{\"categoryLimits\":[{\"categoryId\":\"" + GROCERIES
                + "\",\"limit\":{\"amount\":\"5.00\",\"currency\":\"EUR\"},\"householdId\":\""
                + UUID.randomUUID() + "\"}]}", null)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(putRaw(user, start, "{\"categoryLimits\":[null]}", null)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(putRaw(user, start, "{\"categoryLimits\":[{\"limit\":{\"amount\":\"5.00\","
                + "\"currency\":\"EUR\"}}]}", null)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(count(household)).isZero();
    }

    @Test
    void BR_HH_03_a_category_of_another_household_or_unknown_is_404_and_nothing_is_stored() {
        UUID user = users.active();
        UUID stranger = users.active();
        UUID household = createHousehold(user);
        createHousehold(stranger);
        String foreign = createCategory(stranger, "Secret hobby");
        LocalDate start = firstPeriod(household);

        for (String category : new String[] {foreign, UUID.randomUUID().toString()}) {
            MvcTestResult result = put(user, start, body("100.00", line(category, "10.00")), null);
            assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                    .isEqualTo("CATEGORY_NOT_FOUND");
            assertThat(text(result)).doesNotContain("Secret hobby");
        }
        assertThat(count(household)).isZero();
        // same on update of an existing budget
        put(user, start, body("100.00"), null);
        assertThat(put(user, start, body("100.00", line(foreign, "10.00")), "\"0\"")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lineCount(household)).isZero();
    }

    @Test
    void BR_CAT_03_a_new_line_on_an_archived_category_is_rejected_but_an_existing_one_stays_valid() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        String pets = createCategory(user, "Pets");
        put(user, start, body("500.00", line(pets, "40.00")), null);
        archive(user, pets);

        // a limit on the archived category cannot be created in another period ...
        LocalDate next = jdbc.sql("SELECT period_start FROM household.budget_period WHERE household_id = :h "
                + "AND period_start > :s ORDER BY period_start LIMIT 1").param("h", household).param("s", start)
                .query(LocalDate.class).single();
        assertThat(put(user, next, body("500.00", line(pets, "40.00")), null)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("BUDGET_CATEGORY_ARCHIVED");
        // ... while the existing line stays, may be re-sent and may be removed
        assertThat(get(user, start)).hasStatus(HttpStatus.OK);
        assertThat(json(get(user, start)).get("categoryLimits")).hasSize(1);
        assertThat(put(user, start, body("500.00", line(pets, "45.00")), "\"0\"")).hasStatus(HttpStatus.OK);
        assertThat(put(user, start, body("500.00", line(GROCERIES, "10.00"), line(pets, "45.00")), "\"1\""))
                .hasStatus(HttpStatus.OK);
        assertThat(put(user, start, body("500.00", line(GROCERIES, "10.00")), "\"2\"")).hasStatus(HttpStatus.OK);
        // once removed, the archived category cannot come back
        assertThat(put(user, start, body("500.00", line(GROCERIES, "10.00"), line(pets, "45.00")), "\"3\""))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void BR_BUD_07_categoryLimits_replaces_the_lines_audits_the_change_and_bumps_the_version() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        String pets = createCategory(user, "Pets");
        UUID id = UUID.fromString(json(put(user, start, body("500.00", line(GROCERIES, "100.00")), null)).get("id")
                .asString());

        MvcTestResult updated = put(user, start, body("500.00", line(GROCERIES, "150.00"), line(pets, "20.00")),
                "\"0\"");

        assertThat(updated).hasStatus(HttpStatus.OK);
        assertThat(updated.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"1\"");
        assertThat(json(updated).get("categoryLimits")).hasSize(2);
        assertThat(lineCount(household)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT action FROM budget.audit_event WHERE entity_id = :id ORDER BY occurred_at, id")
                .param("id", id).query(String.class).list()).containsExactly("CREATE", "UPDATE");
        assertThat(jdbc.sql("""
                SELECT changes -> 'categoryLimitsMinor' -> 'old' ->> :c FROM budget.audit_event
                WHERE entity_id = :id AND action = 'UPDATE'""").param("c", GROCERIES).param("id", id)
                .query(String.class).single()).isEqualTo("10000");
        assertThat(jdbc.sql("""
                SELECT changes -> 'categoryLimitsMinor' -> 'new' ->> :c FROM budget.audit_event
                WHERE entity_id = :id AND action = 'UPDATE'""").param("c", pets).param("id", id)
                .query(String.class).single()).isEqualTo("2000");
        assertThat(listener.updated()).anySatisfy(event -> assertThat(event.budgetId()).isEqualTo(id));

        // omitted lines are removed, an omitted categoryLimits leaves them untouched
        assertThat(json(put(user, start, body("500.00", line(pets, "20.00")), "\"1\"")).get("categoryLimits"))
                .hasSize(1);
        MvcTestResult untouched = putRaw(user, start, body("600.00"), "\"2\"");
        assertThat(json(untouched).get("categoryLimits")).hasSize(1);
        assertThat(untouched.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"3\"");
        // sending the same lines again changes nothing
        MvcTestResult same = put(user, start, body("600.00", line(pets, "20.00")), "\"3\"");
        assertThat(same).hasStatus(HttpStatus.OK);
        assertThat(same.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"3\"");
        // an empty array removes every line once an overall limit exists
        assertThat(json(putRaw(user, start, "{\"overallLimit\":{\"amount\":\"600.00\",\"currency\":\"EUR\"},"
                + "\"categoryLimits\":[]}", "\"3\"")).get("categoryLimits")).isEmpty();
        assertThat(lineCount(household)).isZero();
    }

    @Test
    void BR_BUD_07_category_limit_updates_require_if_match() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, body("500.00", line(GROCERIES, "100.00")), null);

        assertThat(put(user, start, body("500.00", line(GROCERIES, "200.00")), null))
                .hasStatus(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(put(user, start, body("500.00", line(GROCERIES, "200.00")), "\"9\""))
                .hasStatus(HttpStatus.PRECONDITION_FAILED);
        assertThat(jdbc.sql("SELECT limit_minor FROM budget.budget_category WHERE household_id = :h")
                .param("h", household).query(Long.class).single()).isEqualTo(10_000L);
    }

    @Test
    void BR_HH_03_the_budget_lines_of_another_household_are_invisible_and_untouchable() {
        UUID owner = users.active();
        UUID stranger = users.active();
        UUID household = createHousehold(owner);
        UUID other = createHousehold(stranger);
        LocalDate start = firstPeriod(household);
        put(owner, start, body("500.00", line(GROCERIES, "123.45")), null);

        MvcTestResult read = get(stranger, start);
        assertThat(read).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(text(read)).doesNotContain("123.45");
        // the stranger's PUT only ever acts on the stranger's own household
        assertThat(put(stranger, start, body("10.00", line(GROCERIES, "1.00")), null)).hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.sql("SELECT limit_minor FROM budget.budget_category WHERE household_id = :h")
                .param("h", household).query(Long.class).single()).isEqualTo(12_345L);
        assertThat(lineCount(other)).isEqualTo(1);
    }

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.put().uri("/api/v1/budgets/2026-10-01").contentType(MediaType.APPLICATION_JSON)
                .content(body("10.00", line(GROCERIES, "5.00"))).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_HH_10_a_dissolved_household_rejects_category_limit_writes_and_still_reads_them() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, body("500.00", line(GROCERIES, "100.00")), null);
        dissolve(household, user);

        assertThat(put(user, start, body("500.00", line(GROCERIES, "200.00")), "\"0\""))
                .hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code")
                .isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(jdbc.sql("SELECT limit_minor FROM budget.budget_category WHERE household_id = :h")
                .param("h", household).query(Long.class).single()).isEqualTo(10_000L);
        assertThat(json(get(user, start)).get("categoryLimits")).hasSize(1);
    }

    @Test
    void BR_BUD_07_concurrent_updates_with_the_same_version_apply_exactly_once() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, body("500.00", line(GROCERIES, "100.00")), null);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String amount = (200 + i) + ".00";
                results.add(pool.submit(() -> {
                    go.await();
                    return put(user, start, body("500.00", line(GROCERIES, amount)), "\"0\"").getResponse()
                            .getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).containsOnlyOnce(200);
            assertThat(statuses.stream().filter(s -> s != 200)).allMatch(s -> s == 412);
            assertThat(lineCount(household)).isEqualTo(1);
            assertThat(jdbc.sql("SELECT version FROM budget.budget WHERE household_id = :h").param("h", household)
                    .query(Long.class).single()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }


    // ------------------------------------------------------------------ per-category consumption - Issue #86

    @Test
    void BR_BUD_03_05_06_category_consumption_counts_only_household_items_of_that_category() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 2, now())").param("h", household).param("u", partner).update();
        LocalDate start = firstPeriod(household);
        String pets = createCategory(user, "Pets");
        String untouched = createCategory(user, "Hobbies");
        put(user, start, body("1000.00", line(GROCERIES, "100.00"), line(pets, "50.00"), line(untouched, "30.00")),
                null);
        spend(user, "EXPENSE", "SHARED", GROCERIES, "85.00", start);
        spend(partner, "EXPENSE", "SHARED", GROCERIES, "30.00", start);
        spend(user, "REFUND", "SHARED", GROCERIES, "5.00", start);
        spend(user, "EXPENSE", "PERSONAL", GROCERIES, "999.00", start); // personal: never in a household figure
        spend(partner, "EXPENSE", "PERSONAL", pets, "999.00", start);
        spend(user, "EXPENSE", "SHARED", pets, "20.00", start);
        spend(user, "EXPENSE", "SHARED", pets, "400.00", start.minusDays(1)); // outside the period

        for (UUID caller : List.of(user, partner)) {
            JsonNode limits = json(get(caller, start)).get("categoryLimits");
            JsonNode groceries = consumptionOf(limits, GROCERIES);
            assertThat(groceries.get("scope").asString()).isEqualTo("HOUSEHOLD");
            assertThat(groceries.get("consumed").get("amount").asString()).isEqualTo("110.00");
            assertThat(groceries.get("remaining").get("amount").asString()).isEqualTo("-10.00");
            assertThat(groceries.get("percentage").asString()).isEqualTo("110.0");
            assertThat(groceries.get("status").asString()).isEqualTo("EXCEEDED"); // over-budget category
            JsonNode petsConsumption = consumptionOf(limits, pets);
            assertThat(petsConsumption.get("consumed").get("amount").asString()).isEqualTo("20.00");
            assertThat(petsConsumption.get("percentage").asString()).isEqualTo("40.0");
            assertThat(petsConsumption.get("status").asString()).isEqualTo("ON_TRACK");
            JsonNode none = consumptionOf(limits, untouched); // no expense: zero, not absent
            assertThat(none.get("consumed").get("amount").asString()).isEqualTo("0.00");
            assertThat(none.get("percentage").asString()).isEqualTo("0.0");
            assertThat(none.get("status").asString()).isEqualTo("ON_TRACK");
        }
    }

    @Test
    void BR_BUD_06_category_status_is_warning_from_80_percent_inclusive() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, body(null, line(GROCERIES, "100.00")), null);
        spend(user, "EXPENSE", "SHARED", GROCERIES, "80.00", start);

        JsonNode consumption = consumptionOf(json(get(user, start)).get("categoryLimits"), GROCERIES);

        assertThat(consumption.get("percentage").asString()).isEqualTo("80.0");
        assertThat(consumption.get("status").asString()).isEqualTo("WARNING");
    }

    @Test
    void BR_MON_09_category_consumption_is_exact_for_large_amounts() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, body(null, line(GROCERIES, "9000000.00")), null);
        for (int i = 0; i < 3; i++) {
            spend(user, "EXPENSE", "SHARED", GROCERIES, "999999.99", start); // near the per-expense maximum
        }

        JsonNode consumption = consumptionOf(json(get(user, start)).get("categoryLimits"), GROCERIES);

        assertThat(consumption.get("consumed").get("amount").asString()).isEqualTo("2999999.97");
        assertThat(consumption.get("remaining").get("amount").asString()).isEqualTo("6000000.03");
        assertThat(consumption.get("percentage").asString()).isEqualTo("33.3");
    }

    @Test
    void BR_EXP_07_BR_HH_03_another_households_category_spending_never_leaks() {
        UUID user = users.active();
        UUID other = users.active();
        UUID household = createHousehold(user);
        UUID otherHousehold = createHousehold(other);
        LocalDate start = firstPeriod(household);
        put(user, start, body(null, line(GROCERIES, "100.00")), null);
        spend(other, "EXPENSE", "SHARED", GROCERIES, "77.00", firstPeriod(otherHousehold));

        JsonNode consumption = consumptionOf(json(get(user, start)).get("categoryLimits"), GROCERIES);

        assertThat(consumption.get("consumed").get("amount").asString()).isEqualTo("0.00");
    }

    @Test
    void the_response_of_a_put_carries_no_category_consumption() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        JsonNode created = json(put(user, start, body(null, line(GROCERIES, "100.00")), null));

        assertThat(isAbsent(created.get("categoryLimits").get(0).get("consumption"))).isTrue();
    }

    private static JsonNode consumptionOf(JsonNode limits, String categoryId) {
        for (JsonNode limit : limits) {
            if (categoryId.equals(limit.get("categoryId").asString())) {
                return limit.get("consumption");
            }
        }
        throw new AssertionError("no limit for category " + categoryId);
    }

    private void spend(UUID payer, String kind, String sharing, String category, String amount, LocalDate date) {
        String money = "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}";
        String request = "{\"kind\":\"" + kind + "\",\"amount\":" + money + ",\"date\":\"" + date
                + "\",\"paidByUserId\":\"" + payer + "\",\"sharingType\":\"" + sharing
                + "\",\"items\":[{\"categoryId\":\"" + category + "\",\"amount\":" + money + "}]}";
        assertThat(mvc.post().uri("/api/v1/expenses").header(HttpHeaders.AUTHORIZATION, tokens.bearer(payer))
                .contentType(MediaType.APPLICATION_JSON).content(request).exchange()).hasStatus(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------ helpers

    private static String line(String categoryId, String amount) {
        return "{\"categoryId\":\"" + categoryId + "\",\"limit\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}}";
    }

    private static String body(String overall, String... lines) {
        String limit = overall == null ? "" : "\"overallLimit\":{\"amount\":\"" + overall
                + "\",\"currency\":\"EUR\"}";
        String categories = lines.length == 0 ? "" : "\"categoryLimits\":[" + String.join(",", lines) + "]";
        return "{" + limit + (!limit.isEmpty() && !categories.isEmpty() ? "," : "") + categories + "}";
    }

    private MvcTestResult put(UUID user, LocalDate start, String body, String ifMatch) {
        return putRaw(user, start, body, ifMatch);
    }

    private MvcTestResult putRaw(UUID user, LocalDate start, String body, String ifMatch) {
        var request = mvc.put().uri("/api/v1/budgets/" + start).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private MvcTestResult get(UUID user, LocalDate start) {
        return mvc.get().uri("/api/v1/budgets/" + start).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private LocalDate firstPeriod(UUID household) {
        return jdbc.sql("SELECT min(period_start) FROM household.budget_period WHERE household_id = :h")
                .param("h", household).query(LocalDate.class).single();
    }

    private int count(UUID household) {
        return jdbc.sql("SELECT count(*) FROM budget.budget WHERE household_id = :h").param("h", household)
                .query(Integer.class).single();
    }

    private int lineCount(UUID household) {
        return jdbc.sql("SELECT count(*) FROM budget.budget_category WHERE household_id = :h").param("h", household)
                .query(Integer.class).single();
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull();
    }

    private String text(MvcTestResult result) {
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

    private void archive(UUID user, String category) {
        assertThat(mvc.patch().uri("/api/v1/categories/" + category)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\": true}").exchange())
                .hasStatus(HttpStatus.OK);
    }

    private void dissolve(UUID household, UUID user) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();
    }
}
