package com.couplefinance.expense.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.expense.application.ExpenseDeletedProbeListener;
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

/** Delete and restore - Issue #20 (BR-EXP-03, 07, 09, 10, 11, BR-NOT-02/03, BR-HH-10). */
@IntegrationTest
class DeleteRestoreExpenseIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
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
    ExpenseDeletedProbeListener listener;

    @Test
    void BR_EXP_11_delete_is_logical_audited_emits_the_event_and_excludes_the_expense_from_reads() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID id = create(user, "SHARED", user, "12.50", null);

        assertThat(delete(user, id)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbc.sql("SELECT deleted_by FROM expense.expense WHERE id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(user);
        assertThat(get(user, id)).hasStatus(HttpStatus.NOT_FOUND);
        JsonNode list = json(mvc.get().uri("/api/v1/expenses?scope=HOUSEHOLD&limit=100")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange());
        assertThat(list.get("items")).isEmpty();
        assertThat(jdbc.sql("SELECT action FROM expense.audit_event WHERE entity_id = :id ORDER BY occurred_at, id")
                .param("id", id).query(String.class).list()).containsExactly("CREATE", "DELETE");
        assertThat(listener.deleted()).anySatisfy(event -> {
            assertThat(event.expenseId()).isEqualTo(id);
            assertThat(event.householdId()).isEqualTo(household);
            assertThat(event.deletedBy()).isEqualTo(user);
            assertThat(event.ownerUserId()).isNull();
        });
        // deleting again, or deleting an unknown expense, is a 404
        assertThat(delete(user, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(user, UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_EXP_11_restore_brings_the_expense_back_audits_and_emits_the_event() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "12.50", "Carrefour");
        assertThat(delete(user, id)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult restored = restore(user, id);

        assertThat(restored).hasStatus(HttpStatus.OK);
        JsonNode json = json(restored);
        assertThat(json.get("id").asString()).isEqualTo(id.toString());
        assertThat(json.get("version").asLong()).isEqualTo(2L);
        assertThat(restored.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"2\"");
        assertThat(get(user, id)).hasStatus(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT deleted_at FROM expense.expense WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).optional()).isEmpty();
        JsonNode audit = json(mvc.get().uri("/api/v1/expenses/" + id + "/audit")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange()).get("items");
        assertThat(audit).extracting(e -> e.get("action").asString()).containsExactly("CREATE", "DELETE", "RESTORE");
        assertThat(listener.restored()).anySatisfy(event -> assertThat(event.expenseId()).isEqualTo(id));
        // a live expense cannot be restored
        assertThat(restore(user, id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_EXP_11_restore_is_possible_until_90_days_after_deletion_then_404() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = create(user, "SHARED", user, "12.50", null);
        assertThat(delete(user, id)).hasStatus(HttpStatus.NO_CONTENT);

        backdateDeletion(id, 91);
        assertThat(restore(user, id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
        backdateDeletion(id, 89);
        assertThat(restore(user, id)).hasStatus(HttpStatus.OK);
    }

    @Test
    void BR_EXP_03_an_expense_with_live_refunds_cannot_be_deleted_until_they_are_deleted() {
        UUID user = users.active();
        createHousehold(user);
        UUID original = create(user, "SHARED", user, "10.00", null);
        UUID refund = createRefund(user, original, "4.00");

        assertThat(delete(user, original)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_HAS_LIVE_REFUNDS");
        assertThat(get(user, original)).hasStatus(HttpStatus.OK);

        assertThat(delete(user, refund)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(user, original)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void BR_EXP_03_restoring_a_refund_rechecks_the_original_under_lock() {
        UUID user = users.active();
        createHousehold(user);
        UUID original = create(user, "SHARED", user, "10.00", null);
        UUID first = createRefund(user, original, "6.00");
        assertThat(delete(user, first)).hasStatus(HttpStatus.NO_CONTENT);
        UUID second = createRefund(user, original, "5.00");

        // 5.00 live + 6.00 > 10.00
        assertThat(restore(user, first)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_REFUND_EXCEEDS_ORIGINAL");
        assertThat(delete(user, second)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(restore(user, first)).hasStatus(HttpStatus.OK);

        // a refund whose original is deleted cannot come back before the original
        assertThat(delete(user, first)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(user, original)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(restore(user, first)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_REFUND_ORIGINAL_INVALID");
        assertThat(restore(user, original)).hasStatus(HttpStatus.OK);
        assertThat(restore(user, first)).hasStatus(HttpStatus.OK);
    }

    @Test
    void BR_EXP_03_a_refund_created_concurrently_with_the_deletion_of_its_original_never_leaves_an_orphan()
            throws Exception {
        UUID user = users.active();
        createHousehold(user);
        for (int round = 0; round < 5; round++) {
            UUID original = create(user, "SHARED", user, "10.00", null);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> deletion = pool.submit(() -> {
                    start.await();
                    return delete(user, original).getResponse().getStatus();
                });
                Future<Integer> refund = pool.submit(() -> {
                    start.await();
                    return mvc.post().uri("/api/v1/expenses").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                            .contentType(MediaType.APPLICATION_JSON).content(refundBody(user, original, "4.00"))
                            .exchange().getResponse().getStatus();
                });
                start.countDown();
                int deleted = deletion.get();
                int refunded = refund.get();

                long liveRefunds = jdbc.sql("SELECT count(*) FROM expense.expense WHERE refund_of_expense_id = :id "
                        + "AND deleted_at IS NULL").param("id", original).query(Long.class).single();
                boolean originalDeleted = jdbc.sql(
                        "SELECT deleted_at IS NOT NULL FROM expense.expense WHERE id = :id")
                        .param("id", original).query(Boolean.class).single();
                assertThat(originalDeleted && liveRefunds > 0)
                        .as("deleted=%d refunded=%d", deleted, refunded).isFalse();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void BR_EXP_09_any_member_deletes_and_restores_a_shared_expense_and_the_event_names_the_creator() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        UUID id = create(user, "SHARED", user, "12.50", null);

        assertThat(delete(partner, id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(listener.deleted()).anySatisfy(event -> {
            assertThat(event.expenseId()).isEqualTo(id);
            assertThat(event.deletedBy()).isEqualTo(partner);
            assertThat(event.createdBy()).isEqualTo(user); // BR-NOT-03: the creator is informed
        });
        assertThat(restore(partner, id)).hasStatus(HttpStatus.OK);
        assertThat(restore(user, id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_EXP_07_BR_NOT_02_the_partner_gets_404_on_a_personal_expense_and_its_event_names_no_creator() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        UUID id = create(owner, "PERSONAL", owner, "12.50", "Secret shop");

        assertThat(delete(partner, id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(get(owner, id)).hasStatus(HttpStatus.OK);
        assertThat(delete(owner, id)).hasStatus(HttpStatus.NO_CONTENT);
        MvcTestResult partnerRestore = restore(partner, id);
        assertThat(partnerRestore).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(text(partnerRestore)).doesNotContain("Secret shop");
        assertThat(listener.deleted()).anySatisfy(event -> {
            assertThat(event.expenseId()).isEqualTo(id);
            assertThat(event.ownerUserId()).isEqualTo(owner);
            assertThat(event.createdBy()).isNull();
        });
        assertThat(restore(owner, id)).hasStatus(HttpStatus.OK);
    }

    @Test
    void another_household_gets_404() {
        UUID owner = users.active();
        UUID stranger = users.active();
        createHousehold(owner);
        createHousehold(stranger);
        UUID id = create(owner, "SHARED", owner, "12.50", null);

        assertThat(delete(stranger, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(owner, id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(restore(stranger, id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.delete().uri("/api/v1/expenses/" + UUID.randomUUID()).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/expenses/" + UUID.randomUUID() + "/restore").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_HH_10_delete_and_restore_on_a_dissolved_household_are_rejected() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID id = create(user, "SHARED", user, "5.00", null);
        UUID deleted = create(user, "SHARED", user, "6.00", null);
        assertThat(delete(user, deleted)).hasStatus(HttpStatus.NO_CONTENT);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();

        assertThat(delete(user, id)).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code")
                .isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(restore(user, deleted)).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code")
                .isEqualTo("HOUSEHOLD_READ_ONLY");
    }

    // ------------------------------------------------------------------ helpers

    private void backdateDeletion(UUID id, int daysAgo) {
        jdbc.sql("UPDATE expense.expense SET deleted_at = now() - make_interval(days => :days) WHERE id = :id")
                .param("days", daysAgo).param("id", id).update();
    }

    private MvcTestResult delete(UUID user, UUID id) {
        return mvc.delete().uri("/api/v1/expenses/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private MvcTestResult restore(UUID user, UUID id) {
        return mvc.post().uri("/api/v1/expenses/" + id + "/restore")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private MvcTestResult get(UUID user, UUID id) {
        return mvc.get().uri("/api/v1/expenses/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private static String refundBody(UUID paidBy, UUID original, String amount) {
        return "{\"kind\":\"REFUND\",\"refundOf\":\"" + original + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"},\"date\":\"" + TODAY + "\",\"paidByUserId\":\"" + paidBy + "\"}";
    }

    private UUID createRefund(UUID user, UUID original, String amount) {
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(refundBody(user, original, amount)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private UUID create(UUID user, String sharing, UUID paidBy, String amount, String merchant) {
        String body = "{\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},\"date\":\"" + TODAY
                + "\",\"paidByUserId\":\"" + paidBy + "\",\"sharingType\":\"" + sharing + "\",\"items\":"
                + "[{\"categoryId\":\"" + GROCERIES + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}}]" + (merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"")
                + "}";
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
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
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", user).update();
    }
}
