package com.couplefinance.analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

/** Chart series (Issue #32): BR-ANA-01, BR-ANA-02, BR-SCP-01..03, BR-EXP-07, BR-HH-03. */
@IntegrationTest
class ChartSeriesIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    private static final String HOUSING = "019a0000-0000-7000-8000-000000000002";
    private static final String TREND = "/api/v1/analytics/trend?scope=";

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
    void BR_ANA_01_02_daily_cumulative_with_refunds_deleted_and_personal_expenses_and_the_budget_limit() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        LocalDate start = pastPeriods(household).get(0);
        seed(household, user, "EXPENSE", 10_000, GROCERIES, start, false, false);
        seed(household, partner, "EXPENSE", 5_000, HOUSING, start.plusDays(2), false, false);
        seed(household, user, "REFUND", 2_000, GROCERIES, start.plusDays(3), false, false);
        seed(household, user, "EXPENSE", 99_000, GROCERIES, start.plusDays(1), false, true); // deleted
        seed(household, partner, "EXPENSE", 77_700, HOUSING, start.plusDays(1), true, false); // partner PERSONAL
        seed(household, user, "EXPENSE", 33_300, HOUSING, start.plusDays(1), true, false); // own PERSONAL
        putBudget(user, start, "1000.00");

        for (UUID caller : List.of(user, partner)) {
            JsonNode body = json(get(caller, daily(start, "HOUSEHOLD")));
            assertThat(body.get("scope").asString()).isEqualTo("HOUSEHOLD");
            assertThat(body.get("periodStart").asString()).isEqualTo(start.toString());
            assertThat(body.get("limit").get("amount").asString()).isEqualTo("1000.00");
            JsonNode points = body.get("points");
            assertThat(points.size())
                    .isEqualTo((int) ChronoUnit.DAYS.between(start, LocalDate.parse(body.get("periodEnd").asString())));
            assertThat(amount(points, 0)).isEqualTo("100.00");
            assertThat(amount(points, 1)).isEqualTo("100.00"); // zero day, no PERSONAL leakage
            assertThat(amount(points, 2)).isEqualTo("150.00");
            assertThat(amount(points, 3)).isEqualTo("130.00");
            assertThat(amount(points, points.size() - 1)).isEqualTo("130.00");
            assertThat(points.get(1).get("date").asString()).isEqualTo(start.plusDays(1).toString());
        }
    }

    @Test
    void BR_SCP_02_personal_daily_series_is_the_owners_only_and_has_no_limit() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        LocalDate start = pastPeriods(household).get(0);
        seed(household, user, "EXPENSE", 4_000, GROCERIES, start, true, false);
        seed(household, user, "EXPENSE", 7_000, GROCERIES, start.plusDays(1), false, false); // shared
        seed(household, partner, "EXPENSE", 8_800, HOUSING, start.plusDays(1), true, false); // partner PERSONAL
        putBudget(user, start, "1000.00");

        JsonNode mine = json(get(user, daily(start, "PERSONAL")));
        assertThat(mine.get("scope").asString()).isEqualTo("PERSONAL");
        assertThat(mine.get("limit") == null || mine.get("limit").isNull()).isTrue();
        assertThat(amount(mine.get("points"), 1)).isEqualTo("40.00");

        JsonNode theirs = json(get(partner, daily(start, "PERSONAL")));
        assertThat(amount(theirs.get("points"), 0)).isEqualTo("0.00");
        assertThat(amount(theirs.get("points"), 1)).isEqualTo("88.00");
    }

    @Test
    void the_current_period_series_stops_today_in_the_household_timezone() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate today = today(household);
        LocalDate start = periods(household).stream().filter(p -> !p.isAfter(today)).reduce((a, b) -> b)
                .orElseThrow();
        JsonNode points = json(get(user, daily(start, "HOUSEHOLD"))).get("points");
        assertThat(points.get(points.size() - 1).get("date").asString()).isEqualTo(today.toString());
    }

    @Test
    void trend_lists_the_last_periods_oldest_first_with_net_totals_and_scope_isolation() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        List<LocalDate> past = pastPeriods(household);
        LocalDate p0 = past.get(past.size() - 2);
        LocalDate p1 = past.get(past.size() - 1);
        setTrackingStart(household, p0);
        seed(household, user, "EXPENSE", 10_000, GROCERIES, p0, false, false);
        seed(household, user, "REFUND", 1_000, GROCERIES, p0, false, false);
        seed(household, partner, "EXPENSE", 55_500, GROCERIES, p0, true, false); // partner PERSONAL
        seed(household, user, "EXPENSE", 20_000, GROCERIES, p1, false, false);
        seed(household, user, "EXPENSE", 30_000, GROCERIES, p1, false, true); // deleted

        JsonNode trend = json(get(user, TREND + "HOUSEHOLD"));
        assertThat(trend.get("scope").asString()).isEqualTo("HOUSEHOLD");
        JsonNode periods = trend.get("periods");
        assertThat(periods.size()).isBetween(2, 6);
        List<String> starts = new ArrayList<>();
        periods.forEach(p -> starts.add(p.get("periodStart").asString()));
        assertThat(starts).isSorted();
        assertThat(periods.get(periods.size() - 1).get("current").asBoolean()).isTrue();
        assertThat(totalOf(periods, p0)).isEqualTo("90.00");
        assertThat(totalOf(periods, p1)).isEqualTo("200.00");

        JsonNode partnersPersonal = json(get(partner, TREND + "PERSONAL")).get("periods");
        assertThat(totalOf(partnersPersonal, p0)).isEqualTo("555.00");
        JsonNode ownersPersonal = json(get(user, TREND + "PERSONAL")).get("periods");
        assertThat(totalOf(ownersPersonal, p0)).isEqualTo("0.00");
    }

    @Test
    void trend_has_at_most_six_periods_and_none_before_the_tracking_start() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        List<LocalDate> past = pastPeriods(household);
        setTrackingStart(household, LocalDate.of(2000, 1, 1));
        assertThat(json(get(user, TREND + "HOUSEHOLD")).get("periods").size()).isLessThanOrEqualTo(6);

        LocalDate last = past.get(past.size() - 1);
        setTrackingStart(household, last);
        JsonNode periods = json(get(user, TREND + "HOUSEHOLD")).get("periods");
        assertThat(periods.get(0).get("periodStart").asString()).isEqualTo(last.toString());
        assertThat(periods.size()).isEqualTo(2);

        setTrackingStart(household, LocalDate.now().plusYears(1));
        assertThat(json(get(user, TREND + "HOUSEHOLD")).get("periods").size()).isZero();
    }

    @Test
    void validation_authentication_and_non_disclosure() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = periods(household).get(0);
        UUID other = users.active();
        UUID otherHousehold = createHousehold(other);
        UUID stranger = users.active(); // no household

        assertThat(mvc.get().uri(daily(start, "HOUSEHOLD")).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(TREND + "HOUSEHOLD").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(get(user, "/api/v1/analytics/trend")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(user, TREND + "ALL")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(user, "/api/v1/analytics/periods/" + start + "/daily-cumulative"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(user, "/api/v1/analytics/periods/nope/daily-cumulative?scope=HOUSEHOLD"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(user, daily(start.plusDays(1), "HOUSEHOLD"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(stranger, daily(start, "HOUSEHOLD"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(stranger, TREND + "PERSONAL")).hasStatus(HttpStatus.NOT_FOUND);

        MvcTestResult ok = get(user, TREND + "HOUSEHOLD");
        assertThat(ok).hasStatus(HttpStatus.OK);
        assertThat(ok.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        // another household never sees this household's spending
        LocalDate past = pastPeriods(household).get(0);
        seed(household, user, "EXPENSE", 12_300, GROCERIES, past, false, false);
        LocalDate otherPast = pastPeriods(otherHousehold).get(0);
        JsonNode theirs = json(get(other, daily(otherPast, "HOUSEHOLD")));
        assertThat(amount(theirs.get("points"), 0)).isEqualTo("0.00");
    }

    // ------------------------------------------------------------------ helpers

    private static String daily(LocalDate start, String scope) {
        return "/api/v1/analytics/periods/" + start + "/daily-cumulative?scope=" + scope;
    }

    private static String amount(JsonNode points, int index) {
        return points.get(index).get("cumulative").get("amount").asString();
    }

    private static String totalOf(JsonNode periods, LocalDate start) {
        for (JsonNode p : periods) {
            if (p.get("periodStart").asString().equals(start.toString())) {
                return p.get("total").get("amount").asString();
            }
        }
        throw new AssertionError("period " + start + " not in the trend");
    }

    private MvcTestResult get(UUID user, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private void putBudget(UUID user, LocalDate start, String limit) {
        assertThat(mvc.put().uri("/api/v1/budgets/" + start)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"overallLimit\":{\"amount\":\"" + limit + "\",\"currency\":\"EUR\"}}").exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    private List<LocalDate> periods(UUID household) {
        return new ArrayList<>(jdbc.sql("SELECT period_start FROM household.budget_period WHERE household_id = :h "
                + "ORDER BY period_start").param("h", household).query(LocalDate.class).list());
    }

    /** Periods that ended on or before today in the household timezone, oldest first. */
    private List<LocalDate> pastPeriods(UUID household) {
        return new ArrayList<>(jdbc.sql("SELECT period_start FROM household.budget_period WHERE household_id = :h "
                + "AND period_end <= :today ORDER BY period_start").param("h", household)
                .param("today", today(household)).query(LocalDate.class).list());
    }

    private LocalDate today(UUID household) {
        String zone = jdbc.sql("SELECT timezone FROM household.household WHERE id = :h").param("h", household)
                .query(String.class).single();
        return LocalDate.now(ZoneId.of(zone));
    }

    private void setTrackingStart(UUID household, LocalDate date) {
        jdbc.sql("UPDATE household.household SET tracking_start_date = :d WHERE id = :h").param("d", date)
                .param("h", household).update();
    }

    private void seed(UUID household, UUID payer, String kind, long amountMinor, String category, LocalDate date,
            boolean personal, boolean deleted) {
        jdbc.sql("""
                WITH e AS (
                    INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                        paid_by_user_id, owner_user_id, source, created_at, created_by, updated_at, updated_by,
                        deleted_at, deleted_by)
                    VALUES (:id, :h, :kind, :amount, 'EUR', :date, :payer, CAST(:owner AS uuid), 'MANUAL', now(),
                        :payer, now(), :payer, CASE WHEN :deleted THEN now() END,
                        CASE WHEN :deleted THEN CAST(:payer AS uuid) END)
                    RETURNING id, household_id)
                INSERT INTO expense.expense_item (id, expense_id, household_id, position, category_id, amount_minor)
                SELECT :item, e.id, e.household_id, 1, CAST(:c AS uuid), :amount FROM e
                """).param("id", UUID.randomUUID()).param("item", UUID.randomUUID()).param("h", household)
                .param("kind", kind).param("amount", amountMinor).param("date", date).param("payer", payer)
                .param("owner", personal ? payer : null).param("deleted", deleted).param("c", category).update();
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
        UUID household = UUID.fromString(json(result).get("id").asString());
        addPastPeriods(household, 7);
        return household;
    }

    /** A new household's calendar starts around today: backfills {@code count} monthly periods before it. */
    private void addPastPeriods(UUID household, int count) {
        LocalDate end = periods(household).get(0);
        for (int i = 0; i < count; i++) {
            LocalDate start = end.minusMonths(1);
            jdbc.sql("INSERT INTO household.budget_period (household_id, period_start, period_end) "
                    + "VALUES (:h, :s, :e)").param("h", household).param("s", start).param("e", end).update();
            end = start;
        }
    }

    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 2, now())").param("h", household).param("u", user).update();
    }
}
