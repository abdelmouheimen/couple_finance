package com.couplefinance.analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * Household analytics of a period - Issue #30 (BR-ANA-01..06, BR-SCP-01, BR-SCP-03, BR-MON-05, BR-MON-09,
 * BR-EXP-07, BR-HH-07).
 */
@IntegrationTest
class PeriodAnalyticsIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    private static final String HOUSING = "019a0000-0000-7000-8000-000000000002";
    private static final String UTILITIES = "019a0000-0000-7000-8000-000000000003";
    private static final String TRANSPORT = "019a0000-0000-7000-8000-000000000004";

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

    @Test
    void BR_ANA_01_02_04_05_06_household_figures_exclude_personal_deleted_and_use_paid_by() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        LocalDate start = periods(household).get(0);
        seed(household, user, "EXPENSE", 60_000, GROCERIES, start, false, false);
        seed(household, partner, "EXPENSE", 20_000, HOUSING, start, false, false);
        seed(household, user, "REFUND", 3_000, TRANSPORT, start, false, false);
        seed(household, partner, "EXPENSE", 99_900, UTILITIES, start, true, false); // partner's PERSONAL
        seed(household, user, "EXPENSE", 88_800, UTILITIES, start, true, false); // own PERSONAL
        seed(household, user, "EXPENSE", 30_000, GROCERIES, start, false, true); // deleted
        // created by the partner but paid by the user: counted for the payer (BR-ANA-04)
        seedPaidBy(household, user, partner, 10_000, GROCERIES, start);

        for (UUID caller : List.of(user, partner)) {
            MvcTestResult result = get(caller, start, "HOUSEHOLD");
            assertThat(result).hasStatus(HttpStatus.OK);
            JsonNode body = json(result);
            assertThat(body.get("scope").asString()).isEqualTo("HOUSEHOLD");
            assertThat(body.get("periodStart").asString()).isEqualTo(start.toString());
            assertThat(body.get("total").get("amount").asString()).isEqualTo("870.00");
            assertThat(body.get("total").get("currency").asString()).isEqualTo("EUR");
            // groceries 700.00 and housing 200.00: shares of 900.00; transport -30.00 is shown apart
            JsonNode categories = body.get("categories");
            assertThat(categories).hasSize(2);
            assertThat(categories.get(0).get("categoryId").asString()).isEqualTo(GROCERIES);
            assertThat(categories.get(0).get("total").get("amount").asString()).isEqualTo("700.00");
            assertThat(categories.get(0).get("percentage").asString()).isEqualTo("77.8");
            assertThat(categories.get(1).get("categoryId").asString()).isEqualTo(HOUSING);
            assertThat(categories.get(1).get("percentage").asString()).isEqualTo("22.2");
            assertThat(body.get("negativeCategories")).hasSize(1);
            assertThat(body.get("negativeCategories").get(0).get("categoryId").asString()).isEqualTo(TRANSPORT);
            assertThat(body.get("negativeCategories").get(0).get("total").get("amount").asString())
                    .isEqualTo("-30.00");
            // the PERSONAL-only category never appears anywhere
            assertThat(body.toString()).doesNotContain(UTILITIES).doesNotContain("999.00").doesNotContain("888.00");
            // by payer: the user paid 600 + 100 - 30 = 670, the partner 200
            JsonNode members = body.get("members");
            assertThat(members).hasSize(2);
            assertThat(members.get(0).get("userId").asString()).isEqualTo(user.toString());
            assertThat(members.get(0).get("total").get("amount").asString()).isEqualTo("670.00");
            assertThat(members.get(1).get("userId").asString()).isEqualTo(partner.toString());
            assertThat(members.get(1).get("total").get("amount").asString()).isEqualTo("200.00");
        }
    }

    @Test
    void BR_ANA_03_three_period_average_respects_tracking_start() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        List<LocalDate> periods = periods(household);
        LocalDate reference = periods.get(3);
        seed(household, user, "EXPENSE", 10_000, GROCERIES, periods.get(0), false, false);
        seed(household, user, "EXPENSE", 20_000, GROCERIES, periods.get(1), false, false);
        seed(household, user, "EXPENSE", 30_100, GROCERIES, periods.get(2), false, false);
        seed(household, user, "EXPENSE", 77_000, GROCERIES, reference, false, false);

        setTrackingStart(household, periods.get(0));
        JsonNode three = json(get(user, reference, "HOUSEHOLD"));
        assertThat(three.get("threePeriodAverage").get("periodsUsed").asInt()).isEqualTo(3);
        assertThat(three.get("threePeriodAverage").get("average").get("amount").asString()).isEqualTo("200.33");
        // comparison with the immediately preceding period: (770.00 - 301.00) / 301.00 = 155.8%
        JsonNode previous = three.get("previousPeriod");
        assertThat(previous.get("periodStart").asString()).isEqualTo(periods.get(2).toString());
        assertThat(previous.get("total").get("amount").asString()).isEqualTo("301.00");
        assertThat(previous.get("difference").get("amount").asString()).isEqualTo("469.00");
        assertThat(previous.get("changePercentage").asString()).isEqualTo("155.8");

        setTrackingStart(household, periods.get(1)); // the first period is before tracking: not eligible
        JsonNode two = json(get(user, reference, "HOUSEHOLD"));
        assertThat(two.get("threePeriodAverage").get("periodsUsed").asInt()).isEqualTo(2);
        assertThat(two.get("threePeriodAverage").get("average").get("amount").asString()).isEqualTo("250.50");

        setTrackingStart(household, periods.get(2));
        JsonNode one = json(get(user, reference, "HOUSEHOLD"));
        assertThat(one.get("threePeriodAverage").get("periodsUsed").asInt()).isEqualTo(1);
        assertThat(one.get("threePeriodAverage").get("average").get("amount").asString()).isEqualTo("301.00");

        setTrackingStart(household, reference); // no complete period on or after the tracking start
        JsonNode none = json(get(user, reference, "HOUSEHOLD"));
        assertThat(isAbsent(none.get("threePeriodAverage"))).isTrue();
        assertThat(isAbsent(none.get("previousPeriod"))).isFalse(); // the comparison does not need tracking
    }

    @Test
    void BR_ANA_03_the_average_is_rounded_half_even_once_at_the_end() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        List<LocalDate> periods = periods(household);
        seed(household, user, "EXPENSE", 10_001, GROCERIES, periods.get(1), false, false);
        seed(household, user, "EXPENSE", 10_000, GROCERIES, periods.get(2), false, false);
        setTrackingStart(household, periods.get(1));

        // (10001 + 10000) / 2 = 10000.5 minor units -> 10000 (half to even), not 10001
        JsonNode body = json(get(user, periods.get(3), "HOUSEHOLD"));
        assertThat(body.get("threePeriodAverage").get("average").get("amount").asString()).isEqualTo("100.00");
    }

    @Test
    void BR_ANA_03_personal_and_deleted_spending_never_feed_the_average_or_the_comparison() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        List<LocalDate> periods = periods(household);
        seed(household, user, "EXPENSE", 10_000, GROCERIES, periods.get(2), false, false);
        seed(household, partner, "EXPENSE", 55_500, GROCERIES, periods.get(2), true, false);
        seed(household, user, "EXPENSE", 44_400, GROCERIES, periods.get(2), false, true);
        setTrackingStart(household, periods.get(2));

        for (UUID caller : List.of(user, partner)) {
            JsonNode body = json(get(caller, periods.get(3), "HOUSEHOLD"));
            assertThat(body.get("threePeriodAverage").get("average").get("amount").asString()).isEqualTo("100.00");
            assertThat(body.get("previousPeriod").get("total").get("amount").asString()).isEqualTo("100.00");
            assertThat(body.get("total").get("amount").asString()).isEqualTo("0.00");
            assertThat(body.get("categories")).isEmpty();
            assertThat(isAbsent(body.get("budget"))).isTrue();
        }
    }

    @Test
    void budget_summary_comes_from_the_budget_module_and_is_absent_without_an_overall_limit() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        List<LocalDate> periods = periods(household);
        LocalDate start = periods.get(0);
        seed(household, user, "EXPENSE", 85_000, GROCERIES, start, false, false);
        seed(household, user, "EXPENSE", 99_900, GROCERIES, start, true, false); // PERSONAL: not consumed

        assertThat(isAbsent(json(get(user, start, "HOUSEHOLD")).get("budget"))).isTrue();

        assertThat(mvc.put().uri("/api/v1/budgets/" + start).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"overallLimit\":{\"amount\":\"1000.00\",\"currency\":\"EUR\"}}").exchange())
                .hasStatus(HttpStatus.CREATED);
        JsonNode budget = json(get(user, start, "HOUSEHOLD")).get("budget");
        assertThat(budget.get("limit").get("amount").asString()).isEqualTo("1000.00");
        assertThat(budget.get("consumed").get("amount").asString()).isEqualTo("850.00");
        assertThat(budget.get("remaining").get("amount").asString()).isEqualTo("150.00");
        assertThat(budget.get("percentage").asString()).isEqualTo("85.0");
        assertThat(budget.get("status").asString()).isEqualTo("WARNING");

        // a category-only budget has no overall limit: no summary
        UUID other = users.active();
        UUID otherHousehold = createHousehold(other);
        LocalDate otherStart = periods(otherHousehold).get(0);
        assertThat(mvc.put().uri("/api/v1/budgets/" + otherStart)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(other)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryLimits\":[{\"categoryId\":\"" + GROCERIES
                        + "\",\"limit\":{\"amount\":\"10.00\",\"currency\":\"EUR\"}}]}").exchange())
                .hasStatus(HttpStatus.CREATED);
        assertThat(isAbsent(json(get(other, otherStart, "HOUSEHOLD")).get("budget"))).isTrue();
    }

    @Test
    void BR_ANA_06_a_zero_net_category_stays_in_the_breakdown_with_zero_percent() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = periods(household).get(0);
        seed(household, user, "EXPENSE", 5_000, GROCERIES, start, false, false);
        seed(household, user, "REFUND", 5_000, HOUSING, start, false, false);
        seed(household, user, "EXPENSE", 5_000, HOUSING, start, false, false);

        JsonNode body = json(get(user, start, "HOUSEHOLD"));
        assertThat(body.get("categories")).hasSize(2);
        assertThat(body.get("categories").get(0).get("percentage").asString()).isEqualTo("100.0");
        assertThat(body.get("categories").get(1).get("percentage").asString()).isEqualTo("0.0");
        assertThat(body.get("negativeCategories")).isEmpty();
    }

    @Test
    void an_empty_period_has_a_zero_total_and_no_breakdown() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = periods(household).get(0);

        JsonNode body = json(get(user, start, "HOUSEHOLD"));

        assertThat(body.get("total").get("amount").asString()).isEqualTo("0.00");
        assertThat(body.get("categories")).isEmpty();
        assertThat(body.get("negativeCategories")).isEmpty();
        assertThat(body.get("members")).isEmpty();
    }

    @Test
    void BR_HH_07_a_date_that_does_not_start_a_period_is_404() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = periods(household).get(0);

        for (LocalDate date : List.of(start.plusDays(1), start.minusYears(30), start.plusYears(30))) {
            assertThat(get(user, date, "HOUSEHOLD")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                    .extractingPath("$.code").isEqualTo("ANALYTICS_PERIOD_NOT_FOUND");
        }
    }

    @Test
    void personal_scope_and_unknown_scope_are_400_and_a_missing_scope_too() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = periods(household).get(0);

        assertThat(get(user, start, "PERSONAL")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("ANALYTICS_SCOPE_UNSUPPORTED");
        assertThat(get(user, start, "EVERYTHING")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/analytics/periods/" + start)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/analytics/periods/not-a-date?scope=HOUSEHOLD")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unauthenticated_is_401_and_a_user_without_household_is_404() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = periods(household).get(0);

        assertThat(mvc.get().uri("/api/v1/analytics/periods/" + start + "?scope=HOUSEHOLD").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(get(users.active(), start, "HOUSEHOLD")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_HH_03_another_household_never_sees_this_households_figures_and_responses_are_no_store() {
        UUID user = users.active();
        UUID stranger = users.active();
        UUID household = createHousehold(user);
        UUID strangerHousehold = createHousehold(stranger);
        LocalDate start = periods(household).get(0);
        seed(household, user, "EXPENSE", 12_345, GROCERIES, start, false, false);
        LocalDate strangerStart = periods(strangerHousehold).get(0);

        MvcTestResult own = get(user, start, "HOUSEHOLD");
        MvcTestResult foreign = get(stranger, strangerStart, "HOUSEHOLD");

        assertThat(json(own).get("total").get("amount").asString()).isEqualTo("123.45");
        assertThat(json(foreign).get("total").get("amount").asString()).isEqualTo("0.00");
        assertThat(foreign.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(own.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    // ------------------------------------------------------------------ helpers

    private MvcTestResult get(UUID user, LocalDate start, String scope) {
        return mvc.get().uri("/api/v1/analytics/periods/" + start + "?scope=" + scope)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private List<LocalDate> periods(UUID household) {
        return new ArrayList<>(jdbc.sql("SELECT period_start FROM household.budget_period WHERE household_id = :h "
                + "ORDER BY period_start").param("h", household).query(LocalDate.class).list());
    }

    private void setTrackingStart(UUID household, LocalDate date) {
        jdbc.sql("UPDATE household.household SET tracking_start_date = :d WHERE id = :h").param("d", date)
                .param("h", household).update();
    }

    private void seed(UUID household, UUID payer, String kind, long amountMinor, String category, LocalDate date,
            boolean personal, boolean deleted) {
        insert(household, payer, payer, kind, amountMinor, category, date, personal, deleted);
    }

    /** A SHARED expense created by {@code creator} but paid by {@code payer}. */
    private void seedPaidBy(UUID household, UUID payer, UUID creator, long amountMinor, String category,
            LocalDate date) {
        insert(household, payer, creator, "EXPENSE", amountMinor, category, date, false, false);
    }

    /** One statement, hence one transaction: the deferred items-consistency trigger sees expense and item. */
    private void insert(UUID household, UUID payer, UUID creator, String kind, long amountMinor, String category,
            LocalDate date, boolean personal, boolean deleted) {
        jdbc.sql("""
                WITH e AS (
                    INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                        paid_by_user_id, owner_user_id, source, created_at, created_by, updated_at, updated_by,
                        deleted_at, deleted_by)
                    VALUES (:id, :h, :kind, :amount, 'EUR', :date, :payer, CAST(:owner AS uuid), 'MANUAL', now(),
                        :creator, now(), :creator, CASE WHEN :deleted THEN now() END,
                        CASE WHEN :deleted THEN CAST(:creator AS uuid) END)
                    RETURNING id, household_id)
                INSERT INTO expense.expense_item (id, expense_id, household_id, position, category_id, amount_minor)
                SELECT :item, e.id, e.household_id, 1, CAST(:c AS uuid), :amount FROM e
                """).param("id", UUID.randomUUID()).param("item", UUID.randomUUID()).param("h", household)
                .param("kind", kind).param("amount", amountMinor).param("date", date).param("payer", payer)
                .param("owner", personal ? payer : null).param("creator", creator).param("deleted", deleted)
                .param("c", category).update();
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull();
    }

    private JsonNode json(MvcTestResult result) {
        try {
            return jsonMapper.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    /** Joining by invitation is not used here: seeds the second member directly. */
    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 2, now())").param("h", household).param("u", user).update();
    }
}
