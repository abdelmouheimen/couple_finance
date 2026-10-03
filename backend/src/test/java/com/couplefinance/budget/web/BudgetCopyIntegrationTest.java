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

/** Copy of the previous period's budget - Issue #26 (BR-BUD-01, BR-BUD-02, BR-CAT-03, BR-HH-03, BR-HH-10). */
@IntegrationTest
class BudgetCopyIntegrationTest {

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
    void BR_BUD_02_copies_overall_and_category_limits_records_the_source_and_emits_the_event() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate first = period(household, 0);
        LocalDate second = period(household, 1);
        String pets = createCategory(user, "Pets");
        JsonNode source = json(put(user, first, body("500.00", line(GROCERIES, "120.00"), line(pets, "30.50"))));

        MvcTestResult copy = copy(user, second);

        assertThat(copy).hasStatus(HttpStatus.CREATED);
        assertThat(copy.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"0\"");
        JsonNode budget = json(copy);
        assertThat(budget.get("periodStart").asString()).isEqualTo(second.toString());
        assertThat(budget.get("overallLimit").get("amount").asString()).isEqualTo("500.00");
        assertThat(budget.get("categoryLimits")).hasSize(2);
        assertThat(budget.get("copiedFromBudgetId").asString()).isEqualTo(source.get("id").asString());
        assertThat(budget.get("id").asString()).isNotEqualTo(source.get("id").asString());
        assertThat(json(get(user, second)).get("copiedFromBudgetId").asString())
                .isEqualTo(source.get("id").asString());
        assertThat(jdbc.sql("SELECT copied_from_budget_id FROM budget.budget WHERE household_id = :h "
                + "AND period_start = :s").param("h", household).param("s", second).query(UUID.class).single())
                .isEqualTo(UUID.fromString(source.get("id").asString()));
        UUID copyId = UUID.fromString(budget.get("id").asString());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM budget.audit_event WHERE household_id = :h AND action = 'CREATE'
                AND entity_id = :id""").param("h", household).param("id", copyId).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(listener.created()).anyMatch(event -> event.budgetId().equals(copyId));
        // the source is untouched
        assertThat(json(get(user, first)).get("version").asLong()).isZero();
    }

    @Test
    void BR_CAT_03_lines_of_archived_categories_are_omitted() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String pets = createCategory(user, "Pets");
        put(user, period(household, 0), body("500.00", line(GROCERIES, "120.00"), line(pets, "30.00")));
        archive(user, pets);

        MvcTestResult copy = copy(user, period(household, 1));

        assertThat(copy).hasStatus(HttpStatus.CREATED);
        JsonNode lines = json(copy).get("categoryLimits");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("categoryId").asString()).isEqualTo(GROCERIES);
        // the source budget keeps its line
        assertThat(json(get(user, period(household, 0))).get("categoryLimits")).hasSize(2);
    }

    @Test
    void BR_BUD_02_a_copy_left_without_any_limit_is_rejected_and_stores_nothing() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String pets = createCategory(user, "Pets");
        put(user, period(household, 0), body(null, line(pets, "30.00")));
        archive(user, pets);

        assertThat(copy(user, period(household, 1))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("BUDGET_LIMIT_REQUIRED");
        assertThat(count(household)).isEqualTo(1);
        assertThat(lineCount(household)).isEqualTo(1);
    }

    @Test
    void BR_BUD_02_a_category_only_budget_is_copied_without_overall_limit() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        put(user, period(household, 0), body(null, line(GROCERIES, "80.00")));

        JsonNode budget = json(copy(user, period(household, 1)));

        assertThat(budget.get("overallLimit") == null || budget.get("overallLimit").isNull()).isTrue();
        assertThat(budget.get("categoryLimits")).hasSize(1);
    }

    @Test
    void BR_BUD_02_no_budget_in_the_previous_period_is_404_and_nothing_is_copied_automatically() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        // a budget two periods earlier is not a source (and nothing recurs by itself)
        put(user, period(household, 0), body("500.00"));

        assertThat(copy(user, period(household, 2))).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("PREVIOUS_BUDGET_NOT_FOUND");
        // the first calendar period has no previous period at all
        assertThat(copy(user, period(household, 0))).hasStatus(HttpStatus.CONFLICT); // it has a budget already
        assertThat(get(user, period(household, 1))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(count(household)).isEqualTo(1);
    }

    @Test
    void BR_BUD_02_the_first_calendar_period_has_no_previous_budget_to_copy() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        assertThat(copy(user, period(household, 0))).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("PREVIOUS_BUDGET_NOT_FOUND");
        assertThat(count(household)).isZero();
    }

    @Test
    void BR_BUD_01_a_target_period_with_a_budget_is_409_and_nothing_is_overwritten() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        put(user, period(household, 0), body("500.00"));
        put(user, period(household, 1), body("999.00", line(GROCERIES, "10.00")));

        assertThat(copy(user, period(household, 1))).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("BUDGET_ALREADY_EXISTS");
        JsonNode untouched = json(get(user, period(household, 1)));
        assertThat(untouched.get("overallLimit").get("amount").asString()).isEqualTo("999.00");
        assertThat(untouched.get("version").asLong()).isZero();
        assertThat(untouched.get("copiedFromBudgetId") == null || untouched.get("copiedFromBudgetId").isNull())
                .isTrue();
    }

    @Test
    void BR_BUD_02_a_copy_happens_only_on_request_and_can_be_chained() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        put(user, period(household, 0), body("500.00"));

        assertThat(copy(user, period(household, 1))).hasStatus(HttpStatus.CREATED);

        assertThat(count(household)).isEqualTo(2);
        assertThat(copy(user, period(household, 1))).hasStatus(HttpStatus.CONFLICT);
        assertThat(copy(user, period(household, 2))).hasStatus(HttpStatus.CREATED);
        assertThat(count(household)).isEqualTo(3);
    }

    @Test
    void BR_BUD_01_concurrent_copies_create_exactly_one_budget() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        put(user, period(household, 0), body("500.00", line(GROCERIES, "10.00")));
        LocalDate target = period(household, 1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(copy(user, target).getResponse().getStatus());
            }));
        }
        start.countDown();
        int created = 0;
        int conflicts = 0;
        for (Future<HttpStatus> result : results) {
            HttpStatus status = result.get();
            if (status == HttpStatus.CREATED) {
                created++;
            } else if (status == HttpStatus.CONFLICT) {
                conflicts++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(count(household)).isEqualTo(2);
        assertThat(lineCount(household)).isEqualTo(2);
    }

    @Test
    void BR_HH_03_another_household_sees_neither_the_source_nor_the_copy() {
        UUID owner = users.active();
        UUID stranger = users.active();
        UUID household = createHousehold(owner);
        UUID other = createHousehold(stranger);
        put(owner, period(household, 0), body("500.00"));

        // the stranger's copy only ever acts within the stranger's own household, which has no source budget
        assertThat(copy(stranger, period(other, 1))).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("PREVIOUS_BUDGET_NOT_FOUND");
        assertThat(count(other)).isZero();
        assertThat(count(household)).isEqualTo(1);
    }

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.post().uri("/api/v1/budgets/2026-10-01/copy-previous").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_HH_10_a_dissolved_household_rejects_the_copy() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        put(user, period(household, 0), body("500.00"));
        dissolve(household, user);

        assertThat(copy(user, period(household, 1))).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(count(household)).isEqualTo(1);
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

    private MvcTestResult put(UUID user, LocalDate start, String body) {
        MvcTestResult result = mvc.put().uri("/api/v1/budgets/" + start)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private MvcTestResult copy(UUID user, LocalDate start) {
        return mvc.post().uri("/api/v1/budgets/" + start + "/copy-previous")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private MvcTestResult get(UUID user, LocalDate start) {
        return mvc.get().uri("/api/v1/budgets/" + start).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    /** The {@code index}-th (0-based) period start of the household calendar. */
    private LocalDate period(UUID household, int index) {
        return jdbc.sql("SELECT period_start FROM household.budget_period WHERE household_id = :h "
                + "ORDER BY period_start LIMIT 1 OFFSET " + index).param("h", household).query(LocalDate.class)
                .single();
    }

    private int count(UUID household) {
        return jdbc.sql("SELECT count(*) FROM budget.budget WHERE household_id = :h").param("h", household)
                .query(Integer.class).single();
    }

    private int lineCount(UUID household) {
        return jdbc.sql("SELECT count(*) FROM budget.budget_category WHERE household_id = :h").param("h", household)
                .query(Integer.class).single();
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
