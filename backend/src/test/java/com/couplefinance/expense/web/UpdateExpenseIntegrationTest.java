package com.couplefinance.expense.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.expense.application.ExpenseUpdatedProbeListener;
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

/** {@code PUT /api/v1/expenses/{id}} - Issue #18 (BR-EXP-03, 07, 09, 10, 12, 14, BR-HH-10). */
@IntegrationTest
class UpdateExpenseIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    private static final String HOUSING = "019a0000-0000-7000-8000-000000000002";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Paris"));

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
    ExpenseUpdatedProbeListener listener;

    @Test
    void BR_EXP_12_update_returns_the_new_etag_increments_the_version_audits_and_emits_the_event() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID id = create(user, "SHARED", user, "12.50", items(GROCERIES, "10.00", HOUSING, "2.50"), "Carrefour");

        MvcTestResult result = put(user, id, "\"0\"", body("20.00", TODAY.minusDays(1), user, "SHARED",
                items(GROCERIES, "15.00", HOUSING, "5.00"), "Lidl", "new note"));

        assertThat(result).hasStatus(HttpStatus.OK).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        JsonNode json = json(result);
        assertThat(json.get("version").asLong()).isEqualTo(1L);
        assertThat(json.get("amount").get("amount").asString()).isEqualTo("20.00");
        assertThat(json.get("merchant").asString()).isEqualTo("Lidl");
        assertThat(json.get("updatedBy").asString()).isEqualTo(user.toString());
        assertThat(json.get("items")).hasSize(2);
        assertThat(get(user, id).getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"1\"");

        JsonNode audit = json(getAudit(user, id)).get("items");
        assertThat(audit).hasSize(2);
        JsonNode entry = audit.get(1);
        assertThat(entry.get("action").asString()).isEqualTo("UPDATE");
        assertThat(entry.get("actorUserId").asString()).isEqualTo(user.toString());
        assertThat(entry.get("changes").get("amountMinor").get("old").asLong()).isEqualTo(1250L);
        assertThat(entry.get("changes").get("amountMinor").get("new").asLong()).isEqualTo(2000L);
        assertThat(entry.get("changes").get("merchant").get("old").asString()).isEqualTo("Carrefour");
        assertThat(entry.get("changes").has("paidByUserId")).as("unchanged fields are not listed").isFalse();
        assertThat(listener.received()).anySatisfy(event -> {
            assertThat(event.expenseId()).isEqualTo(id);
            assertThat(event.householdId()).isEqualTo(household);
            assertThat(event.ownerBefore()).isNull();
            assertThat(event.ownerAfter()).isNull();
        });
    }

    @Test
    void BR_EXP_12_a_change_of_items_only_still_increments_the_version() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "12.50", items(GROCERIES, "10.00", HOUSING, "2.50"), null);

        MvcTestResult result = put(user, id, "\"0\"", body("12.50", TODAY, user, "SHARED",
                items(GROCERIES, "7.50", HOUSING, "5.00"), null, null));

        assertThat(result).hasStatus(HttpStatus.OK).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(jdbc.sql("SELECT sum(amount_minor) FROM expense.expense_item WHERE expense_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1250L);
    }

    @Test
    void BR_EXP_12_a_missing_if_match_is_428_and_a_stale_one_is_412() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "12.50", items(GROCERIES, "12.50"), null);
        String body = body("13.00", TODAY, user, "SHARED", items(GROCERIES, "13.00"), null, null);

        assertThat(put(user, id, null, body)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
                .bodyJson().extractingPath("$.code").isEqualTo("IF_MATCH_REQUIRED");
        assertThat(put(user, id, "\"0\"", body)).hasStatus(HttpStatus.OK);
        assertThat(put(user, id, "\"0\"", body("14.00", TODAY, user, "SHARED", items(GROCERIES, "14.00"), null,
                null))).hasStatus(HttpStatus.PRECONDITION_FAILED).bodyJson().extractingPath("$.code")
                .isEqualTo("VERSION_CONFLICT");
        assertThat(amountMinor(id)).isEqualTo(1300L);
    }

    @Test
    void BR_EXP_12_concurrent_updates_with_the_same_version_let_exactly_one_succeed() throws Exception {
        UUID user = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "12.50", items(GROCERIES, "12.50"), null);
        int contenders = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                String amount = (20 + i) + ".00";
                results.add(pool.submit(() -> {
                    start.await();
                    return put(user, id, "\"0\"", body(amount, TODAY, user, "SHARED", items(GROCERIES, amount),
                            null, null)).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1L);
            assertThat(statuses.stream().filter(s -> s == 412).count()).isEqualTo(contenders - 1L);
            assertThat(jdbc.sql("SELECT version FROM expense.expense WHERE id = :id").param("id", id)
                    .query(Long.class).single()).isEqualTo(1L);
            assertThat(jdbc.sql("SELECT count(*) FROM expense.audit_event WHERE entity_id = :id AND "
                    + "action = 'UPDATE'").param("id", id).query(Long.class).single()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void BR_EXP_09_the_partner_can_edit_a_shared_expense() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        UUID id = create(user, "SHARED", user, "12.50", items(GROCERIES, "12.50"), null);

        MvcTestResult result = put(partner, id, "\"0\"", body("13.00", TODAY, user, "SHARED",
                items(GROCERIES, "13.00"), null, null));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(json(result).get("updatedBy").asString()).isEqualTo(partner.toString());
    }

    @Test
    void BR_EXP_09_only_the_payer_can_switch_sharing() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        UUID id = create(user, "SHARED", user, "12.50", items(GROCERIES, "12.50"), null);

        assertThat(put(partner, id, "\"0\"", body("12.50", TODAY, user, "PERSONAL", items(GROCERIES, "12.50"),
                null, null))).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_SHARING_CHANGE_FORBIDDEN");
        assertThat(put(partner, id, "\"0\"", body("12.50", TODAY, partner, "PERSONAL", items(GROCERIES, "12.50"),
                null, null))).hasStatus(HttpStatus.FORBIDDEN);
        // the payer can
        assertThat(put(user, id, "\"0\"", body("12.50", TODAY, user, "PERSONAL", items(GROCERIES, "12.50"), null,
                null))).hasStatus(HttpStatus.OK);
        assertThat(get(partner, id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_EXP_10_the_audit_of_a_visibility_switch_is_visible_to_the_owner_only() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        UUID id = create(user, "PERSONAL", user, "12.50", items(GROCERIES, "12.50"), "Secret shop");

        assertThat(put(user, id, "\"0\"", body("13.00", TODAY, user, "SHARED", items(GROCERIES, "13.00"),
                "Secret shop", null))).hasStatus(HttpStatus.OK);

        // the expense is shared now: the partner sees it, but not the entries made while it was personal
        JsonNode partnerAudit = json(getAudit(partner, id)).get("items");
        assertThat(partnerAudit).isEmpty();
        assertThat(json(getAudit(user, id)).get("items")).hasSize(2);
        assertThat(listener.received()).anySatisfy(event -> {
            assertThat(event.expenseId()).isEqualTo(id);
            assertThat(event.ownerBefore()).isEqualTo(user);
            assertThat(event.ownerAfter()).isNull();
        });
    }

    @Test
    void BR_EXP_07_the_partner_gets_404_when_editing_a_personal_expense_and_nothing_leaks() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        UUID id = create(owner, "PERSONAL", owner, "12.50", items(GROCERIES, "12.50"), "Secret shop");
        String body = body("13.00", TODAY, partner, "PERSONAL", items(GROCERIES, "13.00"), null, null);

        MvcTestResult personal = put(partner, id, "\"0\"", body);
        MvcTestResult unknown = put(partner, UUID.randomUUID(), "\"0\"", body);
        MvcTestResult noIfMatch = put(partner, id, null, body);

        assertThat(personal).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(unknown).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(noIfMatch).hasStatus(HttpStatus.NOT_FOUND); // 404 before 428: no existence disclosure
        assertThat(json(personal).get("detail").asString()).isEqualTo(json(unknown).get("detail").asString());
        assertThat(text(personal)).doesNotContain("Secret shop");
        assertThat(amountMinor(id)).isEqualTo(1250L);
    }

    @Test
    void another_household_gets_404() {
        UUID owner = users.active();
        UUID stranger = users.active();
        createHousehold(owner);
        createHousehold(stranger);
        UUID id = create(owner, "SHARED", owner, "12.50", items(GROCERIES, "12.50"), null);

        assertThat(put(stranger, id, "\"0\"", body("13.00", TODAY, stranger, "SHARED", items(GROCERIES, "13.00"),
                null, null))).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(amountMinor(id)).isEqualTo(1250L);
    }

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.put().uri("/api/v1/expenses/" + UUID.randomUUID()).header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_EXP_14_an_unchanged_item_on_a_since_archived_category_stays_valid_but_changed_or_new_ones_do_not() {
        UUID user = users.active();
        createHousehold(user);
        String pets = createCategory(user, "Pets");
        UUID id = create(user, "SHARED", user, "5.00", items(pets, "2.00", GROCERIES, "3.00"), null);
        assertThat(mvc.patch().uri("/api/v1/categories/" + pets).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\": true}").exchange()).hasStatus(HttpStatus.OK);

        // Pets 2.00 unchanged: valid (only the groceries item changes)
        assertThat(put(user, id, "\"0\"", body("6.00", TODAY, user, "SHARED", items(pets, "2.00", GROCERIES, "4.00"),
                null, null))).hasStatus(HttpStatus.OK);
        // Pets amount changed: rejected
        assertThat(put(user, id, "\"1\"", body("6.00", TODAY, user, "SHARED", items(pets, "3.00", GROCERIES, "3.00"),
                null, null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_CATEGORY_ARCHIVED");
        // a new item on the archived category: rejected
        UUID other = create(user, "SHARED", user, "5.00", items(GROCERIES, "5.00"), null);
        assertThat(put(user, other, "\"0\"", body("5.00", TODAY, user, "SHARED", items(pets, "2.00", GROCERIES,
                "3.00"), null, null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_CATEGORY_ARCHIVED");
    }

    @Test
    void BR_EXP_14_a_category_of_another_household_is_404() {
        UUID user = users.active();
        UUID stranger = users.active();
        createHousehold(user);
        createHousehold(stranger);
        String foreign = createCategory(stranger, "Foreign");
        UUID id = create(user, "SHARED", user, "5.00", items(GROCERIES, "5.00"), null);

        assertThat(put(user, id, "\"0\"", body("5.00", TODAY, user, "SHARED", items(foreign, "5.00"), null, null)))
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_NOT_FOUND");
    }

    @Test
    void BR_EXP_08_items_must_sum_and_BR_EXP_06_date_and_BR_EXP_07_payer_are_validated_like_creation() {
        UUID user = users.active();
        UUID stranger = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "5.00", items(GROCERIES, "5.00"), null);

        assertThat(put(user, id, "\"0\"", body("6.00", TODAY, user, "SHARED", items(GROCERIES, "5.00"), null, null)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_ITEMS_SUM_MISMATCH");
        assertThat(put(user, id, "\"0\"", body("5.00", TODAY.plusDays(2), user, "SHARED", items(GROCERIES, "5.00"),
                null, null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_DATE_OUT_OF_RANGE");
        assertThat(put(user, id, "\"0\"", body("5.00", TODAY, stranger, "SHARED", items(GROCERIES, "5.00"), null,
                null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_PAID_BY_INVALID");
        assertThat(put(user, id, "\"0\"", "{\"amount\":{\"amount\":\"5.001\",\"currency\":\"EUR\"},\"date\":\""
                + TODAY + "\",\"paidByUserId\":\"" + user + "\",\"sharingType\":\"SHARED\",\"items\":"
                + items(GROCERIES, "5.00") + "}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(amountMinor(id)).isEqualTo(500L);
    }

    @Test
    void household_creator_and_kind_cannot_be_supplied_by_the_client() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "5.00", items(GROCERIES, "5.00"), null);
        String body = body("5.00", TODAY, user, "SHARED", items(GROCERIES, "5.00"), null, null);

        assertThat(put(user, id, "\"0\"", body.replace("{\"amount\"", "{\"kind\":\"REFUND\",\"amount\""))).hasStatus(
                HttpStatus.BAD_REQUEST);
        assertThat(put(user, id, "\"0\"", body.replace("{\"amount\"", "{\"householdId\":\"" + UUID.randomUUID()
                + "\",\"amount\""))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void a_deleted_expense_cannot_be_edited() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "5.00", items(GROCERIES, "5.00"), null);
        jdbc.sql("UPDATE expense.expense SET deleted_at = now(), deleted_by = :user WHERE id = :id")
                .param("user", user).param("id", id).update();

        assertThat(put(user, id, "\"0\"", body("6.00", TODAY, user, "SHARED", items(GROCERIES, "6.00"), null,
                null))).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
    }

    @Test
    void BR_EXP_03_an_original_cannot_drop_below_its_live_refunds_and_a_refund_cannot_exceed_its_original() {
        UUID user = users.active();
        createHousehold(user);
        UUID original = create(user, "SHARED", user, "10.00", items(GROCERIES, "10.00"), null);
        MvcTestResult refund = mvc.post().uri("/api/v1/expenses").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"REFUND\",\"refundOf\":\"" + original
                        + "\",\"amount\":{\"amount\":\"4.00\",\"currency\":\"EUR\"},\"date\":\"" + TODAY
                        + "\",\"paidByUserId\":\"" + user + "\"}").exchange();
        assertThat(refund).hasStatus(HttpStatus.CREATED);
        UUID refundId = UUID.fromString(json(refund).get("id").asString());

        assertThat(put(user, original, "\"0\"", body("3.00", TODAY, user, "SHARED", items(GROCERIES, "3.00"), null,
                null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_REFUND_EXCEEDS_ORIGINAL");
        assertThat(put(user, original, "\"0\"", body("10.00", TODAY, user, "PERSONAL", items(GROCERIES, "10.00"),
                null, null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_REFUND_VISIBILITY_MISMATCH");
        assertThat(put(user, refundId, "\"0\"", body("10.01", TODAY, user, "SHARED", items(GROCERIES, "10.01"),
                null, null))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_REFUND_EXCEEDS_ORIGINAL");
        assertThat(put(user, refundId, "\"0\"", body("10.00", TODAY, user, "SHARED", items(GROCERIES, "10.00"),
                null, null))).hasStatus(HttpStatus.OK);
    }

    @Test
    void BR_HH_10_writes_on_a_dissolved_household_are_rejected() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID id = create(user, "SHARED", user, "5.00", items(GROCERIES, "5.00"), null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();

        assertThat(put(user, id, "\"0\"", body("6.00", TODAY, user, "SHARED", items(GROCERIES, "6.00"), null,
                null))).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code")
                .isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(amountMinor(id)).isEqualTo(500L);
    }

    // ------------------------------------------------------------------ helpers

    private long amountMinor(UUID id) {
        return jdbc.sql("SELECT amount_minor FROM expense.expense WHERE id = :id").param("id", id)
                .query(Long.class).single();
    }

    private MvcTestResult put(UUID user, UUID id, String ifMatch, String body) {
        var request = mvc.put().uri("/api/v1/expenses/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private MvcTestResult get(UUID user, UUID id) {
        return mvc.get().uri("/api/v1/expenses/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private MvcTestResult getAudit(UUID user, UUID id) {
        return mvc.get().uri("/api/v1/expenses/" + id + "/audit")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private static String body(String amount, LocalDate date, UUID paidBy, String sharing, String items,
            String merchant, String note) {
        return "{\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},\"date\":\"" + date
                + "\",\"paidByUserId\":\"" + paidBy + "\",\"sharingType\":\"" + sharing + "\",\"items\":" + items
                + (merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"")
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    /** {@code items(cat1, amount1, cat2, amount2...)} as a JSON array. */
    private static String items(String... categoryAndAmount) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < categoryAndAmount.length; i += 2) {
            sb.append(i == 0 ? "" : ",").append("{\"categoryId\":\"").append(categoryAndAmount[i])
                    .append("\",\"amount\":{\"amount\":\"").append(categoryAndAmount[i + 1])
                    .append("\",\"currency\":\"EUR\"}}");
        }
        return sb.append("]").toString();
    }

    private UUID create(UUID user, String sharing, UUID paidBy, String amount, String items, String merchant) {
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body(amount, TODAY, paidBy, sharing, items, merchant, null)).exchange();
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
