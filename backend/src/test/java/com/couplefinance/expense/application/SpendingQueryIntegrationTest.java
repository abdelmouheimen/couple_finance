package com.couplefinance.expense.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.expense.api.SpendingGrouping;
import com.couplefinance.expense.api.SpendingQuery;
import com.couplefinance.expense.api.SpendingReport;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The spending query facade - Issue #21 (BR-SCP-01..03, BR-EXP-07, BR-EXP-11, BR-ANA-02, BR-ANA-04). Data is
 * created through the API at offsets from the household "today", with fresh users/households per test.
 */
@IntegrationTest
class SpendingQueryIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    private static final String HOUSING = "019a0000-0000-7000-8000-000000000002";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Set<SpendingGrouping> ALL = Set.of(SpendingGrouping.values());

    @Autowired
    SpendingQuery spending;

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
    void BR_SCP_01_refund_decreases_household_spending_and_transfer_and_deleted_are_excluded() {
        Fixture f = fixture();
        create(f.me, "EXPENSE", "SHARED", "20.00", 8, item(GROCERIES, "20.00"));
        String deleted = create(f.partner, "EXPENSE", "SHARED", "50.00", 8, item(GROCERIES, "50.00"));
        create(f.me, "REFUND", "SHARED", "5.00", 7, item(GROCERIES, "5.00"));
        insertTransfer(f.household, f.me, f.partner, 9999);
        jdbc.sql("UPDATE expense.expense SET deleted_at = now(), deleted_by = :u WHERE id = :id")
                .param("u", f.partner).param("id", UUID.fromString(deleted)).update();

        SpendingReport report = query(f, SpendingScope.HOUSEHOLD, 10, 4, ALL);

        assertThat(report.scope()).isEqualTo(SpendingScope.HOUSEHOLD);
        assertThat(report.total().toDecimalString()).isEqualTo("15.00");
        assertThat(report.total().currency().toString()).isEqualTo("EUR");
    }

    @Test
    void BR_SCP_02_personal_spending_is_the_callers_own_and_never_added_to_the_household_figure() {
        Fixture f = fixture();
        create(f.me, "EXPENSE", "SHARED", "10.00", 8, item(GROCERIES, "10.00"));
        create(f.me, "EXPENSE", "PERSONAL", "30.00", 8, item(GROCERIES, "30.00"));
        create(f.me, "REFUND", "PERSONAL", "4.00", 7, item(GROCERIES, "4.00"));
        create(f.partner, "EXPENSE", "PERSONAL", "77.00", 8, item(GROCERIES, "77.00"));

        SpendingReport mine = query(f, SpendingScope.PERSONAL, 10, 4, ALL);
        SpendingReport household = query(f, SpendingScope.HOUSEHOLD, 10, 4, ALL);
        SpendingReport partners = spending.spending(context(f, f.partner), SpendingScope.PERSONAL,
                day(10), day(4), ALL);

        assertThat(mine.scope()).isEqualTo(SpendingScope.PERSONAL);
        assertThat(mine.total().toDecimalString()).isEqualTo("26.00");
        assertThat(mine.byPayer()).extracting(SpendingReport.PayerSpending::userId).containsExactly(f.me);
        assertThat(household.total().toDecimalString()).isEqualTo("10.00");
        assertThat(household.byPayer()).hasSize(1);
        assertThat(household.byDay()).hasSize(1);
        assertThat(household.byCategory()).hasSize(1);
        assertThat(partners.total().toDecimalString()).isEqualTo("77.00");
    }

    @Test
    void the_period_is_half_open_and_a_refund_counts_on_its_own_date() {
        Fixture f = fixture();
        create(f.me, "EXPENSE", "SHARED", "10.00", 10, item(GROCERIES, "10.00")); // on start: in
        create(f.me, "EXPENSE", "SHARED", "20.00", 4, item(GROCERIES, "20.00")); // on end: out
        create(f.me, "EXPENSE", "SHARED", "40.00", 11, item(GROCERIES, "40.00")); // before: out
        create(f.me, "REFUND", "SHARED", "3.00", 4, item(GROCERIES, "3.00")); // refund dated on end: out
        create(f.me, "REFUND", "SHARED", "2.00", 5, item(GROCERIES, "2.00")); // in

        SpendingReport report = query(f, SpendingScope.HOUSEHOLD, 10, 4, Set.of(SpendingGrouping.DAY));

        assertThat(report.total().toDecimalString()).isEqualTo("8.00");
        assertThat(report.byDay()).extracting(SpendingReport.DaySpending::date, d -> d.total().toDecimalString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(day(10), "10.00"),
                        org.assertj.core.groups.Tuple.tuple(day(5), "-2.00"));
        assertThat(report.byCategory()).isEmpty();
        assertThat(report.byPayer()).isEmpty();
    }

    @Test
    void BR_ANA_04_payer_grouping_uses_paid_by_and_category_grouping_sums_items_net_negative_allowed() {
        Fixture f = fixture();
        create(f.me, "EXPENSE", "SHARED", "10.00", 8, item(GROCERIES, "7.00") + "," + item(HOUSING, "3.00"));
        create(f.partner, "EXPENSE", "SHARED", "4.00", 8, item(HOUSING, "4.00"));
        create(f.me, "REFUND", "SHARED", "9.99", 7, item(GROCERIES, "9.99"));

        SpendingReport report = query(f, SpendingScope.HOUSEHOLD, 10, 4, ALL);

        assertThat(report.total().toDecimalString()).isEqualTo("4.01");
        assertThat(report.byPayer()).extracting(SpendingReport.PayerSpending::userId,
                p -> p.total().toDecimalString()).containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(f.me, "0.01"),
                        org.assertj.core.groups.Tuple.tuple(f.partner, "4.00"));
        assertThat(report.byCategory()).extracting(c -> c.categoryId().toString(), c -> c.total().toDecimalString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(GROCERIES, "-2.99"),
                        org.assertj.core.groups.Tuple.tuple(HOUSING, "7.00"));
        assertThat(report.byCategory().stream().map(c -> c.total().minorUnits()).reduce(0L, Long::sum))
                .isEqualTo(report.total().minorUnits());
    }

    @Test
    void an_empty_period_totals_zero() {
        Fixture f = fixture();

        SpendingReport report = query(f, SpendingScope.HOUSEHOLD, 10, 4, ALL);

        assertThat(report.total().isZero()).isTrue();
        assertThat(report.byCategory()).isEmpty();
    }

    @Test
    void an_inverted_or_empty_period_is_rejected() {
        Fixture f = fixture();

        assertThatThrownBy(() -> query(f, SpendingScope.HOUSEHOLD, 4, 4, Set.of()))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> query(f, SpendingScope.HOUSEHOLD, 4, 10, Set.of()))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void another_household_sees_none_of_the_data_in_either_scope() {
        Fixture f = fixture();
        create(f.me, "EXPENSE", "SHARED", "10.00", 8, item(GROCERIES, "10.00"));
        create(f.me, "EXPENSE", "PERSONAL", "10.00", 8, item(GROCERIES, "10.00"));
        UUID stranger = users.active();
        UUID strangerHousehold = createHousehold(stranger);
        HouseholdContext other = new HouseholdContext(new HouseholdId(strangerHousehold), new UserId(stranger),
                HouseholdContext.Status.ACTIVE, HouseholdContext.Role.MEMBER);

        for (SpendingScope scope : SpendingScope.values()) {
            SpendingReport report = spending.spending(other, scope, day(10), day(4), ALL);
            assertThat(report.total().isZero()).isTrue();
            assertThat(report.byCategory()).isEmpty();
            assertThat(report.byPayer()).isEmpty();
            assertThat(report.byDay()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(UUID household, UUID me, UUID partner) {}

    private Fixture fixture() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        UUID partner = users.active();
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", partner)
                .update();
        return new Fixture(household, me, partner);
    }

    private static HouseholdContext context(Fixture f, UUID user) {
        return new HouseholdContext(new HouseholdId(f.household), new UserId(user), HouseholdContext.Status.ACTIVE,
                HouseholdContext.Role.MEMBER);
    }

    private SpendingReport query(Fixture f, SpendingScope scope, int startDaysAgo, int endDaysAgo,
            Set<SpendingGrouping> groupings) {
        return spending.spending(context(f, f.me), scope, day(startDaysAgo), day(endDaysAgo), groupings);
    }

    private static LocalDate day(int daysAgo) {
        return LocalDate.now(PARIS).minusDays(daysAgo);
    }

    private static String item(String category, String amount) {
        return "{\"categoryId\":\"" + category + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}}";
    }

    private String create(UUID payer, String kind, String sharing, String amount, int daysAgo, String items) {
        String body = "{\"kind\":\"" + kind + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"},\"date\":\"" + day(daysAgo) + "\",\"paidByUserId\":\"" + payer
                + "\",\"sharingType\":\"" + sharing + "\",\"items\":[" + items + "]}";
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(payer)).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return jsonMapper.readTree(text(result)).get("id").asString();
    }

    private void insertTransfer(UUID household, UUID from, UUID to, long amountMinor) {
        jdbc.sql("""
                INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                    paid_by_user_id, recipient_user_id, source, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :h, 'TRANSFER', :amount, 'EUR', :date, :from, :to, 'MANUAL', now(), :from, now(), :from)
                """).param("id", UUID.randomUUID()).param("h", household).param("amount", amountMinor)
                .param("date", day(8)).param("from", from).param("to", to).update();
    }

    private static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(jsonMapper.readTree(text(result)).get("id").asString());
    }
}
