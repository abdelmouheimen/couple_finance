package com.couplefinance.expense.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

/** {@code GET /api/v1/expenses/{id}} and {@code /audit} - Issue #17 (BR-EXP-07, BR-EXP-10, BR-HH-10). */
@IntegrationTest
class ExpenseDetailIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    private static final String HOUSING = "019a0000-0000-7000-8000-000000000002";

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
    void BR_EXP_01_reads_an_expense_with_items_etag_and_no_store() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = createExpense(user, "SHARED", user, "Carrefour", "weekly shop");

        MvcTestResult result = get(user, "/api/v1/expenses/" + id);

        assertThat(result).hasStatus(HttpStatus.OK).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode json = json(result);
        assertThat(json.get("id").asString()).isEqualTo(id.toString());
        assertThat(json.get("amount").get("amount").asString()).isEqualTo("12.50");
        assertThat(json.get("sharingType").asString()).isEqualTo("SHARED");
        assertThat(json.get("paidByUserId").asString()).isEqualTo(user.toString());
        assertThat(json.get("createdBy").asString()).isEqualTo(user.toString());
        assertThat(json.get("createdAt").isNull()).isFalse();
        assertThat(json.get("updatedAt").isNull()).isFalse();
        assertThat(json.get("merchant").asString()).isEqualTo("Carrefour");
        assertThat(json.get("note").asString()).isEqualTo("weekly shop");
        assertThat(json.get("items")).hasSize(2);
        assertThat(json.get("items").get(0).get("amount").get("amount").asString()).isEqualTo("10.00");
    }

    @Test
    void unauthenticated_access_is_rejected() {
        UUID id = UUID.randomUUID();
        assertThat(mvc.get().uri("/api/v1/expenses/" + id).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/expenses/" + id + "/audit").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_EXP_07_both_members_read_a_shared_expense_and_its_audit() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        UUID id = createExpense(user, "SHARED", user, null, null);

        assertThat(get(partner, "/api/v1/expenses/" + id)).hasStatus(HttpStatus.OK);
        MvcTestResult audit = get(partner, "/api/v1/expenses/" + id + "/audit");
        assertThat(audit).hasStatus(HttpStatus.OK);
        assertThat(json(audit).get("items")).hasSize(1);
    }

    @Test
    void BR_EXP_10_audit_trail_lists_actor_time_and_changes() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = createExpense(user, "SHARED", user, "Carrefour", null);

        MvcTestResult result = get(user, "/api/v1/expenses/" + id + "/audit");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode entry = json(result).get("items").get(0);
        assertThat(entry.get("action").asString()).isEqualTo("CREATE");
        assertThat(entry.get("actorType").asString()).isEqualTo("USER");
        assertThat(entry.get("actorUserId").asString()).isEqualTo(user.toString());
        assertThat(entry.get("occurredAt").isNull()).isFalse();
        assertThat(entry.get("changes").get("amountMinor").get("new").asLong()).isEqualTo(1250L);
        assertThat(entry.get("changes").get("amountMinor").get("old").isNull()).isTrue();
        assertThat(entry.get("changes").get("merchant").get("new").asString()).isEqualTo("Carrefour");
    }

    @Test
    void BR_EXP_07_a_partner_gets_404_on_a_personal_expense_and_its_audit_like_on_an_unknown_id() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        UUID id = createExpense(owner, "PERSONAL", owner, "Secret shop", "private note");

        MvcTestResult personal = get(partner, "/api/v1/expenses/" + id);
        MvcTestResult unknown = get(partner, "/api/v1/expenses/" + UUID.randomUUID());
        MvcTestResult personalAudit = get(partner, "/api/v1/expenses/" + id + "/audit");

        assertThat(personal).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(unknown).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(personalAudit).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(text(personal)).doesNotContain("Secret shop").doesNotContain("private note");
        assertThat(text(personalAudit)).doesNotContain("Secret shop");
        assertThat(json(personal).get("detail").asString()).isEqualTo(json(unknown).get("detail").asString());
        assertThat(json(personal).get("title").asString()).isEqualTo(json(unknown).get("title").asString());
    }

    @Test
    void BR_EXP_10_the_owner_reads_their_personal_expense_and_its_audit() {
        UUID owner = users.active();
        createHousehold(owner);
        UUID id = createExpense(owner, "PERSONAL", owner, "Secret shop", null);

        assertThat(get(owner, "/api/v1/expenses/" + id)).hasStatus(HttpStatus.OK);
        MvcTestResult audit = get(owner, "/api/v1/expenses/" + id + "/audit");
        assertThat(audit).hasStatus(HttpStatus.OK);
        assertThat(json(audit).get("items")).hasSize(1);
    }

    @Test
    void BR_EXP_10_audit_rows_of_a_personal_expense_are_filtered_by_owner_even_when_the_expense_is_visible() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        UUID id = createExpense(owner, "SHARED", owner, null, null);
        // a later PERSONAL-owned audit row (e.g. a visibility change) must not reach the partner
        jdbc.sql("INSERT INTO expense.audit_event (id, household_id, entity_type, entity_id, owner_user_id, "
                + "action, actor_type, actor_user_id, changes, occurred_at) VALUES (:id, :h, 'EXPENSE', :e, :o, "
                + "'UPDATE', 'USER', :o, CAST('{\"note\":{\"old\":null,\"new\":\"hidden\"}}' AS jsonb), now())")
                .param("id", UUID.randomUUID()).param("h", household).param("e", id).param("o", owner).update();

        assertThat(json(get(partner, "/api/v1/expenses/" + id + "/audit")).get("items")).hasSize(1);
        assertThat(json(get(owner, "/api/v1/expenses/" + id + "/audit")).get("items")).hasSize(2);
    }

    @Test
    void BR_EXP_07_another_household_gets_404() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = createExpense(user, "SHARED", user, null, null);
        UUID stranger = users.active();
        createHousehold(stranger);

        assertThat(get(stranger, "/api/v1/expenses/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(stranger, "/api/v1/expenses/" + id + "/audit")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_EXP_11_a_logically_deleted_expense_is_not_returned() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = createExpense(user, "SHARED", user, null, null);
        jdbc.sql("UPDATE expense.expense SET deleted_at = now(), deleted_by = :user WHERE id = :id")
                .param("user", user).param("id", id).update();

        assertThat(get(user, "/api/v1/expenses/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(user, "/api/v1/expenses/" + id + "/audit")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_HH_10_an_archive_reader_reads_shared_and_own_personal_but_not_the_partners_personal() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(user);
        addMember(household, partner);
        UUID shared = createExpense(user, "SHARED", user, null, null);
        UUID mine = createExpense(user, "PERSONAL", user, null, null);
        UUID theirs = createExpense(partner, "PERSONAL", partner, null, null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id").param("now", now).param("until", now.plusDays(30))
                .param("id", household).update();

        assertThat(get(user, "/api/v1/expenses/" + shared)).hasStatus(HttpStatus.OK);
        assertThat(get(user, "/api/v1/expenses/" + shared + "/audit")).hasStatus(HttpStatus.OK);
        assertThat(get(user, "/api/v1/expenses/" + mine)).hasStatus(HttpStatus.OK);
        assertThat(get(user, "/api/v1/expenses/" + theirs)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(user, "/api/v1/expenses/" + theirs + "/audit")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void a_user_without_household_gets_404() {
        UUID user = users.active();
        assertThat(get(user, "/api/v1/expenses/" + UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
    }

    // ------------------------------------------------------------------ helpers

    private MvcTestResult get(UUID user, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private UUID createExpense(UUID user, String sharing, UUID paidBy, String merchant, String note) {
        String body = "{\"amount\":{\"amount\":\"12.50\",\"currency\":\"EUR\"},\"date\":\""
                + LocalDate.now(ZoneId.of("Europe/Paris")) + "\",\"paidByUserId\":\"" + paidBy
                + "\",\"sharingType\":\"" + sharing + "\",\"items\":["
                + item(GROCERIES, "10.00", "Fruit") + "," + item(HOUSING, "2.50") + "]"
                + (merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"")
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private static String item(String category, String amount) {
        return "{\"categoryId\":\"" + category + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}}";
    }

    private static String item(String category, String amount, String label) {
        return "{\"categoryId\":\"" + category + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"},\"label\":\"" + label + "\"}";
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
