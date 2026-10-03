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
 * Overall budget per period - Issue #24 (BR-BUD-01, BR-BUD-02, BR-BUD-07, BR-HH-03, BR-HH-07, BR-HH-10,
 * BR-MON-02/03/07).
 */
@IntegrationTest
class BudgetIntegrationTest {

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
    void BR_BUD_01_put_creates_the_budget_of_the_period_and_get_reads_it_with_its_etag() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        MvcTestResult created = put(user, start, limit("1500.00"), null);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"0\"");
        JsonNode body = json(created);
        assertThat(body.get("periodStart").asString()).isEqualTo(start.toString());
        assertThat(body.get("periodEnd").asString()).isEqualTo(periodEnd(household, start).toString());
        assertThat(body.get("overallLimit").get("amount").asString()).isEqualTo("1500.00");
        assertThat(body.get("overallLimit").get("currency").asString()).isEqualTo("EUR");
        assertThat(body.get("createdBy").asString()).isEqualTo(user.toString());
        assertThat(body.get("version").asLong()).isZero();

        MvcTestResult read = get(user, start);
        assertThat(read).hasStatus(HttpStatus.OK);
        assertThat(read.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"0\"");
        assertThat(json(read).get("overallLimit").get("amount").asString()).isEqualTo("1500.00");
        assertThat(jdbc.sql("SELECT overall_limit_minor FROM budget.budget WHERE household_id = :h")
                .param("h", household).query(Long.class).single()).isEqualTo(150_000L);
    }

    @Test
    void creating_audits_the_change_and_emits_BudgetCreated() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        UUID id = UUID.fromString(json(put(user, start, limit("1500.00"), null)).get("id").asString());

        assertThat(jdbc.sql("SELECT action || ':' || actor_user_id FROM budget.audit_event WHERE entity_id = :id")
                .param("id", id).query(String.class).list()).containsExactly("CREATE:" + user);
        assertThat(jdbc.sql("SELECT changes ->> 'overallLimitMinor' FROM budget.audit_event WHERE entity_id = :id")
                .param("id", id).query(String.class).single()).contains("1500").doesNotContain("1500.00");
        assertThat(listener.created()).anySatisfy(event -> {
            assertThat(event.budgetId()).isEqualTo(id);
            assertThat(event.householdId()).isEqualTo(household);
            assertThat(event.periodStart()).isEqualTo(start);
        });
    }

    @Test
    void BR_BUD_07_updating_requires_if_match_audits_old_and_new_and_emits_BudgetUpdated() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        UUID id = UUID.fromString(json(put(user, start, limit("1500.00"), null)).get("id").asString());

        MvcTestResult updated = put(user, start, limit("1800.50"), "\"0\"");

        assertThat(updated).hasStatus(HttpStatus.OK);
        assertThat(updated.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"1\"");
        assertThat(json(updated).get("overallLimit").get("amount").asString()).isEqualTo("1800.50");
        assertThat(jdbc.sql("SELECT action FROM budget.audit_event WHERE entity_id = :id ORDER BY occurred_at, id")
                .param("id", id).query(String.class).list()).containsExactly("CREATE", "UPDATE");
        assertThat(jdbc.sql("""
                SELECT (changes -> 'overallLimitMinor' ->> 'old') || '>' || (changes -> 'overallLimitMinor' ->> 'new')
                FROM budget.audit_event WHERE entity_id = :id AND action = 'UPDATE'
                """).param("id", id).query(String.class).single()).isEqualTo("150000>180050");
        assertThat(listener.updated()).anySatisfy(event -> assertThat(event.budgetId()).isEqualTo(id));
        assertThat(jdbc.sql("SELECT count(*) FROM budget.budget WHERE household_id = :h").param("h", household)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void putting_the_same_limit_again_changes_nothing() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        UUID id = UUID.fromString(json(put(user, start, limit("1500.00"), null)).get("id").asString());

        MvcTestResult same = put(user, start, limit("1500.00"), "\"0\"");

        assertThat(same).hasStatus(HttpStatus.OK);
        assertThat(same.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"0\"");
        assertThat(jdbc.sql("SELECT count(*) FROM budget.audit_event WHERE entity_id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void updating_without_if_match_is_428_and_with_a_stale_one_412_and_nothing_changes() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, limit("1500.00"), null);

        assertThat(put(user, start, limit("1600.00"), null)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
                .bodyJson().extractingPath("$.code").isEqualTo("IF_MATCH_REQUIRED");
        assertThat(put(user, start, limit("1600.00"), "\"7\"")).hasStatus(HttpStatus.PRECONDITION_FAILED)
                .bodyJson().extractingPath("$.code").isEqualTo("VERSION_CONFLICT");
        assertThat(put(user, start, limit("1600.00"), "*")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("IF_MATCH_INVALID");
        assertThat(limitMinor(household)).isEqualTo(150_000L);
    }

    @Test
    void if_match_on_a_period_without_budget_is_412_and_creates_nothing() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        assertThat(put(user, firstPeriod(household), limit("1500.00"), "\"0\""))
                .hasStatus(HttpStatus.PRECONDITION_FAILED);
        assertThat(count(household)).isZero();
    }

    @Test
    void BR_BUD_02_a_put_without_an_overall_limit_is_400_and_persists_nothing() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        for (String body : new String[] {"{}", "{\"overallLimit\":null}"}) {
            assertThat(putRaw(user, start, body, null)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("BUDGET_LIMIT_REQUIRED");
        }
        assertThat(count(household)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM budget.audit_event WHERE household_id = :h").param("h", household)
                .query(Integer.class).single()).isZero();

        put(user, start, limit("1500.00"), null);
        // an update that drops the limit is rejected too and modifies nothing (400 comes before 428/412)
        assertThat(putRaw(user, start, "{}", "\"0\"")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("BUDGET_LIMIT_REQUIRED");
        assertThat(limitMinor(household)).isEqualTo(150_000L);
    }

    @Test
    void BR_MON_02_03_07_invalid_amounts_are_400() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        assertThat(put(user, start, limit("0.00"), null)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(put(user, start, limit("-10.00"), null)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(put(user, start, limit("10.001"), null)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("TOO_MANY_DECIMALS");
        assertThat(putRaw(user, start, "{\"overallLimit\":{\"amount\":\"10.00\",\"currency\":\"USD\"}}", null))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("CURRENCY_MISMATCH");
        assertThat(putRaw(user, start, "{\"overallLimit\":{\"amount\":\"10000000000000.01\","
                + "\"currency\":\"EUR\"}}", null)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("AMOUNT_EXCEEDS_MAXIMUM");
        assertThat(putRaw(user, start, "{\"overallLimit\":{\"amount\":15.5,\"currency\":\"EUR\"}}", null))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(putRaw(user, start, "{\"overallLimit\":{\"amount\":\"10.00\",\"currency\":\"EUR\"},"
                + "\"householdId\":\"" + UUID.randomUUID() + "\"}", null)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(count(household)).isZero();
    }

    @Test
    void BR_HH_07_an_unknown_period_start_is_404_for_get_and_put() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);

        // not the start of a period: inside a period, far in the future, far in the past
        for (LocalDate date : new LocalDate[] {start.plusDays(1), start.plusYears(40), start.minusYears(40)}) {
            assertThat(put(user, date, limit("1500.00"), null)).hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson().extractingPath("$.code").isEqualTo("BUDGET_PERIOD_NOT_FOUND");
            assertThat(get(user, date)).hasStatus(HttpStatus.NOT_FOUND);
        }
        assertThat(mvc.get().uri("/api/v1/budgets/not-a-date").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(count(household)).isZero();
    }

    @Test
    void get_of_a_period_without_budget_is_404() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        assertThat(get(user, firstPeriod(household))).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("BUDGET_NOT_FOUND");
    }

    @Test
    void BR_BUD_07_a_past_period_can_be_budgeted_and_each_period_has_its_own_budget() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        List<LocalDate> starts = jdbc.sql("SELECT period_start FROM household.budget_period WHERE household_id = :h "
                + "ORDER BY period_start LIMIT 2").param("h", household).query(LocalDate.class).list();

        assertThat(put(user, starts.get(0), limit("100.00"), null)).hasStatus(HttpStatus.CREATED);
        assertThat(put(user, starts.get(1), limit("200.00"), null)).hasStatus(HttpStatus.CREATED);
        assertThat(count(household)).isEqualTo(2);
    }

    @Test
    void BR_BUD_07_a_past_period_budget_can_be_created_and_edited_and_every_edit_is_audited() {
        UUID user = users.active();
        UUID stranger = users.active();
        UUID household = createHousehold(user);
        createHousehold(stranger);
        LocalDate start = LocalDate.of(2020, 1, 1);
        LocalDate end = LocalDate.of(2020, 2, 1);
        jdbc.sql("INSERT INTO household.budget_period (household_id, period_start, period_end) "
                + "VALUES (:h, :s, :e)").param("h", household).param("s", start).param("e", end).update();

        MvcTestResult created = put(user, start, limit("100.00"), null);
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID id = UUID.fromString(json(created).get("id").asString());
        MvcTestResult edited = put(user, start, limit("150.00"), "\"0\"");

        assertThat(edited).hasStatus(HttpStatus.OK);
        assertThat(json(edited).get("periodStart").asString()).isEqualTo(start.toString());
        assertThat(json(edited).get("periodEnd").asString()).isEqualTo(end.toString());
        assertThat(jdbc.sql("SELECT period_start || '/' || period_end FROM budget.budget WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("2020-01-01/2020-02-01");
        assertThat(jdbc.sql("SELECT action || ':' || actor_user_id || ':' || household_id "
                + "FROM budget.audit_event WHERE entity_id = :id ORDER BY occurred_at, id").param("id", id)
                .query(String.class).list()).containsExactly("CREATE:" + user + ":" + household,
                        "UPDATE:" + user + ":" + household);
        assertThat(jdbc.sql("""
                SELECT (changes -> 'overallLimitMinor' ->> 'old') || '>' || (changes -> 'overallLimitMinor' ->> 'new')
                FROM budget.audit_event WHERE entity_id = :id AND action = 'UPDATE'
                """).param("id", id).query(String.class).single()).isEqualTo("10000>15000");
        // the audit of this household never belongs to another one
        assertThat(jdbc.sql("SELECT count(*) FROM budget.audit_event WHERE entity_id = :id AND household_id <> :h")
                .param("id", id).param("h", household).query(Integer.class).single()).isZero();
        // the stranger cannot reach the past budget
        assertThat(get(stranger, start)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(put(stranger, start, limit("1.00"), "\"1\"")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_HH_03_both_members_create_and_edit_a_budget() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        LocalDate start = firstPeriod(household);

        assertThat(put(user, start, limit("1500.00"), null)).hasStatus(HttpStatus.CREATED);
        assertThat(put(partner, start, limit("1700.00"), "\"0\"")).hasStatus(HttpStatus.OK);
        assertThat(json(get(user, start)).get("updatedBy").asString()).isEqualTo(partner.toString());
        assertThat(limitMinor(household)).isEqualTo(170_000L);
    }

    @Test
    void BR_HH_03_a_budget_of_another_household_is_invisible_and_untouchable() {
        UUID owner = users.active();
        UUID stranger = users.active();
        UUID household = createHousehold(owner);
        UUID other = createHousehold(stranger);
        LocalDate start = firstPeriod(household);
        put(owner, start, limit("1234.56"), null);

        MvcTestResult read = get(stranger, start);
        assertThat(read).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(text(read)).doesNotContain("1234.56");
        // the stranger's own PUT only ever acts on the stranger's household
        MvcTestResult write = put(stranger, firstPeriod(other), limit("99.00"), null);
        assertThat(write).hasStatus(HttpStatus.CREATED);
        assertThat(put(stranger, start, limit("98.00"), "\"0\"")).hasStatus(HttpStatus.OK);
        assertThat(limitMinor(other)).isEqualTo(9_800L);
        assertThat(limitMinor(household)).isEqualTo(123_456L);
    }

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.get().uri("/api/v1/budgets/2026-10-01").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.put().uri("/api/v1/budgets/2026-10-01").contentType(MediaType.APPLICATION_JSON)
                .content(limit("10.00")).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_user_without_household_gets_404() {
        UUID loner = users.active();

        assertThat(get(loner, LocalDate.of(2026, 10, 1))).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
        assertThat(put(loner, LocalDate.of(2026, 10, 1), limit("10.00"), null)).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
    }

    @Test
    void BR_HH_10_a_dissolved_household_rejects_writes_and_an_archive_reader_can_only_read() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        LocalDate other = jdbc.sql("SELECT period_start FROM household.budget_period WHERE household_id = :h "
                + "ORDER BY period_start OFFSET 1 LIMIT 1").param("h", household).query(LocalDate.class).single();
        put(user, start, limit("1500.00"), null);
        dissolve(household, user);

        assertThat(put(user, start, limit("1600.00"), "\"0\"")).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(put(user, other, limit("1600.00"), null)).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(limitMinor(household)).isEqualTo(150_000L);
        assertThat(count(household)).isEqualTo(1);
        MvcTestResult read = get(user, start);
        assertThat(read).hasStatus(HttpStatus.OK);
        assertThat(json(read).get("overallLimit").get("amount").asString()).isEqualTo("1500.00");
    }

    // ------------------------------------------------------------------ consumption - Issue #28

    @Test
    void BR_BUD_03_05_06_consumption_counts_shared_net_of_refunds_and_ignores_personal_and_deleted() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        LocalDate start = firstPeriod(household);
        put(user, start, limit("1000.00"), null);
        createExpense(user, "EXPENSE", "SHARED", "700.00", start);
        createExpense(partner, "EXPENSE", "SHARED", "200.00", start);
        createExpense(user, "REFUND", "SHARED", "50.00", start);
        createExpense(partner, "EXPENSE", "PERSONAL", "999.00", start); // partner's personal: never counted
        createExpense(user, "EXPENSE", "PERSONAL", "888.00", start); // own personal: not household spending
        String deleted = createExpense(user, "EXPENSE", "SHARED", "300.00", start);
        jdbc.sql("UPDATE expense.expense SET deleted_at = now(), deleted_by = :u WHERE id = :id")
                .param("u", user).param("id", UUID.fromString(deleted)).update();

        for (UUID caller : List.of(user, partner)) {
            JsonNode consumption = json(get(caller, start)).get("overallConsumption");
            assertThat(consumption.get("scope").asString()).isEqualTo("HOUSEHOLD");
            assertThat(consumption.get("consumed").get("amount").asString()).isEqualTo("850.00");
            assertThat(consumption.get("consumed").get("currency").asString()).isEqualTo("EUR");
            assertThat(consumption.get("remaining").get("amount").asString()).isEqualTo("150.00");
            assertThat(consumption.get("percentage").asString()).isEqualTo("85.0");
            assertThat(consumption.get("status").asString()).isEqualTo("WARNING");
        }
    }

    @Test
    void BR_BUD_05_06_exceeding_the_limit_gives_negative_remaining_and_exceeded() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, limit("100.00"), null);
        createExpense(user, "EXPENSE", "SHARED", "100.01", start);

        JsonNode consumption = json(get(user, start)).get("overallConsumption");

        assertThat(consumption.get("remaining").get("amount").asString()).isEqualTo("-0.01");
        assertThat(consumption.get("percentage").asString()).isEqualTo("100.0");
        assertThat(consumption.get("status").asString()).isEqualTo("EXCEEDED");
    }

    @Test
    void consumption_is_computed_on_read_with_no_expense_on_track_and_follows_limit_changes() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, limit("100.00"), null);

        JsonNode empty = json(get(user, start)).get("overallConsumption");
        assertThat(empty.get("consumed").get("amount").asString()).isEqualTo("0.00");
        assertThat(empty.get("status").asString()).isEqualTo("ON_TRACK");

        createExpense(user, "EXPENSE", "SHARED", "90.00", start);
        assertThat(json(get(user, start)).get("overallConsumption").get("status").asString()).isEqualTo("WARNING");
        put(user, start, limit("200.00"), "\"0\"");
        JsonNode raised = json(get(user, start)).get("overallConsumption");
        assertThat(raised.get("percentage").asString()).isEqualTo("45.0");
        assertThat(raised.get("status").asString()).isEqualTo("ON_TRACK");
    }

    @Test
    void BR_BUD_03_only_expenses_dated_in_the_period_count() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        LocalDate end = periodEnd(household, start);
        put(user, start, limit("100.00"), null);
        createExpense(user, "EXPENSE", "SHARED", "10.00", start);
        // an expense dated before the period must not count; seeded directly as the API may forbid old dates
        insertSharedExpense(household, user, 5_000L, start.minusDays(1));
        insertSharedExpense(household, user, 7_000L, end); // end is exclusive

        JsonNode consumption = json(get(user, start)).get("overallConsumption");

        assertThat(consumption.get("consumed").get("amount").asString()).isEqualTo("10.00");
    }

    @Test
    void the_response_of_a_put_carries_no_consumption() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        MvcTestResult created = put(user, firstPeriod(household), limit("100.00"), null);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        JsonNode consumption = json(created).get("overallConsumption");
        assertThat(consumption == null || consumption.isNull()).isTrue();
    }

    @Test
    void BR_EXP_07_BR_HH_03_another_households_spending_never_leaks_and_its_budget_is_404() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, limit("100.00"), null);
        UUID stranger = users.active();
        UUID strangerHousehold = createHousehold(stranger);
        createExpense(stranger, "EXPENSE", "SHARED", "60.00", firstPeriod(strangerHousehold));

        assertThat(json(get(user, start)).get("overallConsumption").get("consumed").get("amount").asString())
                .isEqualTo("0.00");
        assertThat(get(stranger, start)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_HH_10_an_archive_reader_of_a_dissolved_household_still_reads_the_consumption() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, limit("100.00"), null);
        createExpense(user, "EXPENSE", "SHARED", "40.00", start);
        dissolve(household, user);

        MvcTestResult read = get(user, start);

        assertThat(read).hasStatus(HttpStatus.OK);
        assertThat(json(read).get("overallConsumption").get("consumed").get("amount").asString())
                .isEqualTo("40.00");
    }

    @Test
    void BR_BUD_03_unauthenticated_read_is_401() {
        assertThat(mvc.get().uri("/api/v1/budgets/2026-01-01").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_BUD_01_concurrent_creation_yields_a_single_budget() throws Exception {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        LocalDate start = firstPeriod(household);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                UUID caller = i % 2 == 0 ? user : partner;
                String amount = (100 + i) + ".00";
                results.add(pool.submit(() -> {
                    go.await();
                    return put(caller, start, limit(amount), null).getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).containsOnlyOnce(201);
            // losers either lose the insert race (412) or find the committed budget and lack its If-Match (428)
            assertThat(statuses.stream().filter(s -> s != 201)).allMatch(s -> s == 412 || s == 428);
            assertThat(count(household)).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM budget.audit_event WHERE household_id = :h")
                    .param("h", household).query(Integer.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrent_updates_with_the_same_version_apply_exactly_once() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LocalDate start = firstPeriod(household);
        put(user, start, limit("1500.00"), null);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String amount = (2000 + i) + ".00";
                results.add(pool.submit(() -> {
                    go.await();
                    return put(user, start, limit(amount), "\"0\"").getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).containsOnlyOnce(200);
            assertThat(statuses.stream().filter(s -> s != 200)).allMatch(s -> s == 412);
            assertThat(jdbc.sql("SELECT version FROM budget.budget WHERE household_id = :h").param("h", household)
                    .query(Long.class).single()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String limit(String amount) {
        return "{\"overallLimit\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}}";
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

    private LocalDate periodEnd(UUID household, LocalDate start) {
        return jdbc.sql("SELECT period_end FROM household.budget_period WHERE household_id = :h "
                + "AND period_start = :s").param("h", household).param("s", start).query(LocalDate.class).single();
    }

    private int count(UUID household) {
        return jdbc.sql("SELECT count(*) FROM budget.budget WHERE household_id = :h").param("h", household)
                .query(Integer.class).single();
    }

    private long limitMinor(UUID household) {
        return jdbc.sql("SELECT overall_limit_minor FROM budget.budget WHERE household_id = :h")
                .param("h", household).query(Long.class).single();
    }

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";

    private String createExpense(UUID payer, String kind, String sharing, String amount, LocalDate date) {
        String money = "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}";
        String body = "{\"kind\":\"" + kind + "\",\"amount\":" + money + ",\"date\":\"" + date
                + "\",\"paidByUserId\":\"" + payer + "\",\"sharingType\":\"" + sharing
                + "\",\"items\":[{\"categoryId\":\"" + GROCERIES + "\",\"amount\":" + money + "}]}";
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(payer)).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return json(result).get("id").asString();
    }

    /** One statement, hence one transaction: the deferred items-consistency trigger sees expense and item. */
    private void insertSharedExpense(UUID household, UUID payer, long amountMinor, LocalDate date) {
        jdbc.sql("""
                WITH e AS (
                    INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                        paid_by_user_id, source, created_at, created_by, updated_at, updated_by)
                    VALUES (:id, :h, 'EXPENSE', :amount, 'EUR', :date, :u, 'MANUAL', now(), :u, now(), :u)
                    RETURNING id, household_id)
                INSERT INTO expense.expense_item (id, expense_id, household_id, position, category_id, amount_minor)
                SELECT :item, e.id, e.household_id, 1, CAST(:c AS uuid), :amount FROM e
                """).param("id", UUID.randomUUID()).param("item", UUID.randomUUID()).param("h", household)
                .param("amount", amountMinor).param("date", date).param("u", payer).param("c", GROCERIES).update();
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

    /** Joining by invitation is not used here: seeds the second member directly. */
    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 2, now())").param("h", household).param("u", user).update();
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
