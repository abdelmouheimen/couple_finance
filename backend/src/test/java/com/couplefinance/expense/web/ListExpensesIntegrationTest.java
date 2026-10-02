package com.couplefinance.expense.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * {@code GET /api/v1/expenses} - Issue #16 (BR-EXP-07, BR-EXP-11, BR-SCP-01..03, features.md F9). Data is created
 * through the API at fixed offsets from the household "today", and scoped to fresh users/households per test.
 */
@IntegrationTest
class ListExpensesIntegrationTest {

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

    // ------------------------------------------------------------------ scope views (BR-EXP-07, BR-SCP)

    @Test
    void BR_SCP_01_the_household_view_lists_only_shared_expenses_and_labels_its_totals() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        UUID partner = users.active();
        addMember(household, partner);
        create(me, "SHARED", "10.00", 1, "Carrefour", null, GROCERIES);
        create(partner, "SHARED", "5.00", 2, "Lidl", null, GROCERIES);
        create(me, "PERSONAL", "99.00", 1, "Secret", null, GROCERIES);
        create(partner, "PERSONAL", "77.00", 1, "Partner secret", null, GROCERIES);

        JsonNode page = json(get(me, "scope=HOUSEHOLD"));

        assertThat(merchants(page)).containsExactly("Carrefour", "Lidl");
        assertThat(page.get("totals").get("scope").asString()).isEqualTo("HOUSEHOLD");
        assertThat(page.get("totals").get("count").asInt()).isEqualTo(2);
        assertThat(page.get("totals").get("net").get("amount").asString()).isEqualTo("15.00");
        assertThat(page.get("totals").get("net").get("currency").asString()).isEqualTo("EUR");
    }

    @Test
    void BR_EXP_07_the_personal_view_lists_only_the_callers_own_personal_expenses() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        UUID partner = users.active();
        addMember(household, partner);
        create(me, "SHARED", "10.00", 1, "Shared", null, GROCERIES);
        create(me, "PERSONAL", "99.00", 1, "Mine", null, GROCERIES);
        create(partner, "PERSONAL", "77.00", 1, "Partner secret", null, GROCERIES);

        JsonNode mine = json(get(me, "scope=PERSONAL"));
        JsonNode theirs = json(get(partner, "scope=PERSONAL"));

        assertThat(merchants(mine)).containsExactly("Mine");
        assertThat(mine.get("totals").get("scope").asString()).isEqualTo("PERSONAL");
        assertThat(mine.get("totals").get("net").get("amount").asString()).isEqualTo("99.00");
        assertThat(merchants(theirs)).containsExactly("Partner secret");
        assertThat(theirs.get("totals").get("count").asInt()).isEqualTo(1);
    }

    @Test
    void BR_EXP_07_a_partner_personal_expense_never_appears_in_lists_counts_totals_or_search() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        UUID partner = users.active();
        addMember(household, partner);
        create(partner, "PERSONAL", "77.00", 1, "Surprise gift", "ring", GROCERIES);

        for (String scope : List.of("HOUSEHOLD", "PERSONAL")) {
            for (String filter : List.of("", "&q=surprise", "&q=ring", "&paidBy=" + partner,
                    "&categoryId=" + GROCERIES, "&kind=EXPENSE")) {
                MvcTestResult result = get(me, "scope=" + scope + filter);
                String body = text(result);
                JsonNode page = jsonMapper.readTree(body);
                assertThat(page.get("items")).isEmpty();
                assertThat(page.get("totals").get("count").asInt()).isZero();
                assertThat(page.get("totals").get("net").get("amount").asString()).isEqualTo("0.00");
                assertThat(body).doesNotContain("Surprise").doesNotContain("77.00");
            }
        }
    }

    @Test
    void BR_SCP_01_refunds_decrease_the_total_and_transfers_are_excluded() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        String original = create(me, "SHARED", "10.00", 3, "Shop", null, GROCERIES);
        post(me, "{\"kind\":\"REFUND\",\"refundOf\":\"" + original + "\",\"amount\":{\"amount\":\"3.00\","
                + "\"currency\":\"EUR\"},\"date\":\"" + today().minusDays(2) + "\",\"paidByUserId\":\"" + me
                + "\"}");
        UUID partner = users.active();
        addMember(household, partner);
        insertTransfer(household, me, partner, 4000);

        JsonNode page = json(get(me, "scope=HOUSEHOLD"));

        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("totals").get("count").asInt()).isEqualTo(2);
        assertThat(page.get("totals").get("net").get("amount").asString()).isEqualTo("7.00");
    }

    @Test
    void BR_SCP_01_net_total_can_be_negative_when_refunds_exceed_expenses_of_the_filter() {
        UUID me = users.active();
        createHousehold(me);
        String original = create(me, "SHARED", "10.00", 20, "Shop", null, GROCERIES);
        post(me, "{\"kind\":\"REFUND\",\"refundOf\":\"" + original + "\",\"amount\":{\"amount\":\"4.00\","
                + "\"currency\":\"EUR\"},\"date\":\"" + today().minusDays(1) + "\",\"paidByUserId\":\"" + me
                + "\"}");

        JsonNode page = json(get(me, "scope=HOUSEHOLD&dateFrom=" + today().minusDays(5)));

        assertThat(page.get("totals").get("net").get("amount").asString()).isEqualTo("-4.00");
    }

    @Test
    void BR_EXP_11_deleted_expenses_are_excluded_from_items_counts_and_totals() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        create(me, "SHARED", "10.00", 1, "Kept", null, GROCERIES);
        String deleted = create(me, "SHARED", "40.00", 1, "Gone", null, GROCERIES);
        jdbc.sql("UPDATE expense.expense SET deleted_at = now(), deleted_by = :user WHERE id = :id")
                .param("user", me).param("id", UUID.fromString(deleted)).update();

        JsonNode page = json(search(me, "Gone"));
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("totals").get("count").asInt()).isZero();

        JsonNode all = json(get(me, "scope=HOUSEHOLD"));
        assertThat(merchants(all)).containsExactly("Kept");
        assertThat(all.get("totals").get("net").get("amount").asString()).isEqualTo("10.00");
        assertThat(household).isNotNull();
    }

    // ------------------------------------------------------------------ filters (F9)

    @Test
    void filters_by_date_range_inclusive_on_both_bounds() {
        UUID me = users.active();
        createHousehold(me);
        create(me, "SHARED", "1.00", 10, "D10", null, GROCERIES);
        create(me, "SHARED", "1.00", 8, "D8", null, GROCERIES);
        create(me, "SHARED", "1.00", 6, "D6", null, GROCERIES);
        create(me, "SHARED", "1.00", 4, "D4", null, GROCERIES);

        JsonNode page = json(get(me, "scope=HOUSEHOLD&dateFrom=" + today().minusDays(8)
                + "&dateTo=" + today().minusDays(6)));

        assertThat(merchants(page)).containsExactly("D6", "D8");
        assertThat(page.get("totals").get("net").get("amount").asString()).isEqualTo("2.00");
    }

    @Test
    void filters_by_category_and_the_total_sums_only_the_items_of_that_category() {
        UUID me = users.active();
        createHousehold(me);
        post(me, expenseJson(me, "SHARED", "10.00", 1, "Mixed", null,
                "[" + item(GROCERIES, "7.00") + "," + item(HOUSING, "3.00") + "]"));
        create(me, "SHARED", "5.00", 2, "Rent", null, HOUSING);

        JsonNode groceries = json(get(me, "scope=HOUSEHOLD&categoryId=" + GROCERIES));
        JsonNode housing = json(get(me, "scope=HOUSEHOLD&categoryId=" + HOUSING));

        assertThat(merchants(groceries)).containsExactly("Mixed");
        assertThat(groceries.get("totals").get("net").get("amount").asString()).isEqualTo("7.00");
        assertThat(merchants(housing)).containsExactly("Mixed", "Rent");
        assertThat(housing.get("totals").get("net").get("amount").asString()).isEqualTo("8.00");
        assertThat(housing.get("totals").get("count").asInt()).isEqualTo(2);
    }

    @Test
    void filters_by_payer_kind_and_receipt() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        UUID partner = users.active();
        addMember(household, partner);
        String mine = create(me, "SHARED", "1.00", 1, "Mine", null, GROCERIES);
        post(me, "{\"amount\":{\"amount\":\"2.00\",\"currency\":\"EUR\"},\"date\":\"" + today().minusDays(2)
                + "\",\"paidByUserId\":\"" + partner + "\",\"merchant\":\"Theirs\",\"items\":["
                + item(GROCERIES, "2.00") + "]}");
        post(me, "{\"kind\":\"REFUND\",\"amount\":{\"amount\":\"0.50\",\"currency\":\"EUR\"},\"date\":\""
                + today() + "\",\"paidByUserId\":\"" + me + "\",\"merchant\":\"Refund\",\"items\":["
                + item(GROCERIES, "0.50") + "]}");
        jdbc.sql("UPDATE expense.expense SET receipt_id = :r WHERE id = :id").param("r", UUID.randomUUID())
                .param("id", UUID.fromString(mine)).update();

        assertThat(merchants(json(get(me, "scope=HOUSEHOLD&paidBy=" + partner)))).containsExactly("Theirs");
        assertThat(merchants(json(get(me, "scope=HOUSEHOLD&kind=REFUND")))).containsExactly("Refund");
        assertThat(merchants(json(get(me, "scope=HOUSEHOLD&kind=EXPENSE")))).containsExactly("Mine", "Theirs");
        assertThat(merchants(json(get(me, "scope=HOUSEHOLD&hasReceipt=true")))).containsExactly("Mine");
        assertThat(merchants(json(get(me, "scope=HOUSEHOLD&hasReceipt=false"))))
                .containsExactly("Refund", "Theirs");
    }

    @Test
    void searches_merchant_and_note_case_insensitively_and_treats_wildcards_literally() {
        UUID me = users.active();
        createHousehold(me);
        create(me, "SHARED", "1.00", 1, "Carrefour City", null, GROCERIES);
        create(me, "SHARED", "1.00", 2, "Boulangerie", "pain de 100% seigle", GROCERIES);
        create(me, "SHARED", "1.00", 3, "Other", "snake_case", GROCERIES);

        assertThat(merchants(json(search(me, "CARREFOUR")))).containsExactly("Carrefour City");
        assertThat(merchants(json(search(me, "seigle")))).containsExactly("Boulangerie");
        assertThat(merchants(json(search(me, "100%")))).containsExactly("Boulangerie");
        assertThat(merchants(json(search(me, "%")))).containsExactly("Boulangerie");
        assertThat(merchants(json(search(me, "_")))).containsExactly("Other");
        assertThat(json(search(me, "' OR 1=1 --")).get("items")).isEmpty();
        assertThat(json(search(me, "  ")).get("items")).hasSize(3);
    }

    @Test
    void invalid_filters_are_rejected() {
        UUID me = users.active();
        createHousehold(me);

        assertThat(get(me, "scope=HOUSEHOLD&dateFrom=2026-02-02&dateTo=2026-01-01")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_FILTER_INVALID");
        assertThat(get(me, "scope=HOUSEHOLD&q=" + "x".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("EXPENSE_FILTER_INVALID");
        assertThat(get(me, "")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(me, "scope=EVERYONE")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(me, "scope=HOUSEHOLD&dateFrom=yesterday")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(me, "scope=HOUSEHOLD&categoryId=nope")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(me, "scope=HOUSEHOLD&kind=TRANSFERS")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ pagination

    @Test
    void paginates_with_a_stable_date_then_id_order_without_gaps_or_duplicates() {
        UUID me = users.active();
        createHousehold(me);
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(create(me, "SHARED", "1.00", i / 3, "M" + i, null, GROCERIES)); // ties on the date
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = json(get(me, "scope=HOUSEHOLD&limit=3" + (cursor == null ? "" : "&cursor=" + cursor)));
            page.get("items").forEach(item -> seen.add(item.get("id").asString()));
            assertThat(page.get("totals").get("count").asInt()).isEqualTo(7); // totals cover the whole set
            cursor = page.has("nextCursor") && !page.get("nextCursor").isNull()
                    ? page.get("nextCursor").asString() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
        List<String> expected = new ArrayList<>();
        jdbc.sql("SELECT id FROM expense.expense WHERE household_id = (SELECT household_id FROM "
                + "household.household_member WHERE user_id = :u) ORDER BY expense_date DESC, id DESC")
                .param("u", me).query(UUID.class).list().forEach(id -> expected.add(id.toString()));
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    void a_page_exactly_filling_the_limit_has_no_next_cursor() {
        UUID me = users.active();
        createHousehold(me);
        create(me, "SHARED", "1.00", 1, "A", null, GROCERIES);
        create(me, "SHARED", "1.00", 2, "B", null, GROCERIES);

        JsonNode page = json(get(me, "scope=HOUSEHOLD&limit=2"));

        assertThat(page.get("items")).hasSize(2);
        assertThat(page.has("nextCursor") && !page.get("nextCursor").isNull()).isFalse();
    }

    @Test
    void the_limit_is_capped_at_100_and_below_one_is_rejected() {
        UUID me = users.active();
        createHousehold(me);
        create(me, "SHARED", "1.00", 1, "A", null, GROCERIES);

        assertThat(get(me, "scope=HOUSEHOLD&limit=1000")).hasStatus(HttpStatus.OK);
        assertThat(get(me, "scope=HOUSEHOLD&limit=0")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_LIMIT");
    }

    @Test
    void invalid_tampered_and_foreign_cursors_are_400() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        UUID partner = users.active();
        addMember(household, partner);
        create(me, "SHARED", "1.00", 1, "A", null, GROCERIES);
        create(me, "SHARED", "1.00", 2, "B", null, GROCERIES);
        create(me, "PERSONAL", "1.00", 2, "C", null, GROCERIES);
        create(me, "PERSONAL", "1.00", 3, "D", null, GROCERIES);
        String cursor = json(get(me, "scope=HOUSEHOLD&limit=1")).get("nextCursor").asString();
        String personalCursor = json(get(me, "scope=PERSONAL&limit=1")).get("nextCursor").asString();

        assertThat(get(me, "scope=HOUSEHOLD&cursor=garbage")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CURSOR");
        assertThat(get(me, "scope=HOUSEHOLD&cursor=" + cursor.substring(0, cursor.length() - 2) + "AA"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(partner, "scope=HOUSEHOLD&cursor=" + cursor)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(me, "scope=PERSONAL&cursor=" + cursor)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(me, "scope=HOUSEHOLD&cursor=" + personalCursor)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(me, "scope=HOUSEHOLD&cursor=" + cursor)).hasStatus(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void unauthenticated_access_is_401() {
        assertThat(mvc.get().uri(URI + "?scope=HOUSEHOLD").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_user_without_household_gets_404() {
        UUID lonely = users.active();

        assertThat(get(lonely, "scope=HOUSEHOLD")).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
    }

    @Test
    void another_household_sees_nothing_of_this_one() {
        UUID me = users.active();
        createHousehold(me);
        create(me, "SHARED", "10.00", 1, "Mine", null, GROCERIES);
        create(me, "PERSONAL", "10.00", 1, "Mine personal", null, GROCERIES);
        UUID stranger = users.active();
        createHousehold(stranger);

        for (String scope : List.of("HOUSEHOLD", "PERSONAL")) {
            JsonNode page = json(get(stranger, "scope=" + scope + "&q=Mine"));
            assertThat(page.get("items")).isEmpty();
            assertThat(page.get("totals").get("count").asInt()).isZero();
        }
    }

    @Test
    void the_response_is_not_cacheable() {
        UUID me = users.active();
        createHousehold(me);

        MvcTestResult result = get(me, "scope=HOUSEHOLD");

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void BR_HH_10_a_former_member_can_read_the_archive_of_a_dissolved_household() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        create(me, "SHARED", "10.00", 1, "Archived", null, GROCERIES);
        create(me, "PERSONAL", "5.00", 1, "Archived personal", null, GROCERIES);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", me).update();

        assertThat(merchants(json(get(me, "scope=HOUSEHOLD")))).containsExactly("Archived");
        assertThat(merchants(json(get(me, "scope=PERSONAL")))).containsExactly("Archived personal");
    }

    // ------------------------------------------------------------------ performance (features.md F9)

    @Test
    void F9_the_history_stays_index_driven_on_a_dataset_above_5000_expenses() {
        UUID me = users.active();
        UUID household = createHousehold(me);
        UUID partner = users.active();
        addMember(household, partner);
        UUID category = UUID.fromString(GROCERIES);
        // One statement: the deferred items-consistency trigger checks expense and items at commit.
        jdbc.sql("""
                WITH seeded AS (
                    SELECT gen_random_uuid() AS id, n FROM generate_series(1, 6000) n
                ), inserted AS (
                    INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                        paid_by_user_id, owner_user_id, merchant_display, merchant_key,
                        merchant_normaliser_version, source, created_at, created_by, updated_at, updated_by)
                    SELECT id, :h, 'EXPENSE', 100 + n, 'EUR', CURRENT_DATE - (n % 900),
                        CASE WHEN n % 5 = 0 THEN :me ELSE :partner END,
                        CASE WHEN n % 5 = 0 THEN :me ELSE NULL END,
                        'Shop ' || n, 'shop ' || n, 1, 'MANUAL', now(),
                        CASE WHEN n % 5 = 0 THEN :me ELSE :partner END, now(),
                        CASE WHEN n % 5 = 0 THEN :me ELSE :partner END
                    FROM seeded
                    RETURNING id
                )
                INSERT INTO expense.expense_item (id, expense_id, household_id, position, category_id, amount_minor)
                SELECT gen_random_uuid(), seeded.id, :h, 1, :c, 100 + seeded.n FROM seeded
                JOIN inserted USING (id)
                """).param("h", household).param("me", me).param("partner", partner).param("c", category)
                .update();
        jdbc.sql("ANALYZE expense.expense").update();

        long start = System.nanoTime();
        JsonNode page = json(get(me, "scope=HOUSEHOLD&limit=100"));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(page.get("items")).hasSize(100);
        assertThat(page.get("totals").get("count").asInt()).isEqualTo(4800);
        assertThat(millis).as("first page of 4800 shared expenses, ms").isLessThan(2000);
        String plan = String.join("\n", jdbc.sql("EXPLAIN SELECT e.expense_date, e.id FROM expense.expense e "
                + "WHERE e.household_id = :h AND e.deleted_at IS NULL AND e.owner_user_id IS NULL "
                + "ORDER BY e.expense_date DESC, e.id DESC LIMIT 101").param("h", household)
                .query(String.class).list());
        assertThat(plan).contains("ix_expense_history").doesNotContain("Sort");
    }

    // ------------------------------------------------------------------ helpers

    private static LocalDate today() {
        return LocalDate.now(PARIS);
    }

    private static String item(String category, String amount) {
        return "{\"categoryId\":\"" + category + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}}";
    }

    private static String expenseJson(UUID payer, String sharing, String amount, int daysAgo, String merchant,
            String note, String items) {
        return "{\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},\"date\":\""
                + today().minusDays(daysAgo) + "\",\"paidByUserId\":\"" + payer + "\",\"sharingType\":\""
                + sharing + "\"" + (merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"")
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + ",\"items\":" + items + "}";
    }

    /** Creates an expense paid by {@code payer} (its creator) with a single item. Returns its id. */
    private String create(UUID payer, String sharing, String amount, int daysAgo, String merchant, String note,
            String category) {
        MvcTestResult result = post(payer, expenseJson(payer, sharing, amount, daysAgo, merchant, note,
                "[" + item(category, amount) + "]"));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return json(result).get("id").asString();
    }

    private void insertTransfer(UUID household, UUID from, UUID to, long amountMinor) {
        jdbc.sql("""
                INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                    paid_by_user_id, recipient_user_id, source, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :h, 'TRANSFER', :amount, 'EUR', :date, :from, :to, 'MANUAL', now(), :from, now(), :from)
                """).param("id", UUID.randomUUID()).param("h", household).param("amount", amountMinor)
                .param("date", today()).param("from", from).param("to", to).update();
    }

    private MvcTestResult get(UUID user, String query) {
        return mvc.get().uri(URI + (query.isEmpty() ? "" : "?" + query))
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    /** The household view filtered by {@code q}, sent as a request parameter (no URL-encoding ambiguity). */
    private MvcTestResult search(UUID user, String q) {
        return mvc.get().uri(URI).param("scope", "HOUSEHOLD").param("q", q)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private MvcTestResult post(UUID user, String body) {
        return mvc.post().uri(URI).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private List<String> merchants(JsonNode page) {
        List<String> merchants = new ArrayList<>();
        page.get("items").forEach(item -> merchants.add(item.has("merchant") ? item.get("merchant").asString() : "(none)"));
        return merchants;
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
        return UUID.fromString(jsonMapper.readTree(text(result)).get("id").asString());
    }

    /** Joining by invitation does not exist yet: seeds the second member directly. */
    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", user).update();
    }
}
