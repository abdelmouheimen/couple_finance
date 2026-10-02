package com.couplefinance.household.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.household.domain.InvitationCode;
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

/** {@code /api/v1/households/me/invitations} — Issue #29 (BR-HH-01, BR-HH-04, BR-HH-10). */
@IntegrationTest
class InvitationIntegrationTest {

    private static final String INVITATIONS = "/api/v1/households/me/invitations";

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
    void creating_returns_the_code_once_and_stores_only_its_hash() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        MvcTestResult result = post(user);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode body = json(result);
        String code = body.get("code").asString();
        UUID id = UUID.fromString(body.get("id").asString());
        assertThat(body.get("expiresAt").asString()).isNotBlank();
        assertThat(body.has("codeHash")).isFalse();

        Map<String, Object> row = jdbc.sql("SELECT * FROM household.invitation WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row.get("household_id")).isEqualTo(household);
        assertThat(row.get("created_by")).isEqualTo(user);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("revoked_at")).isNull();
        assertThat((byte[]) row.get("code_hash")).isEqualTo(InvitationCode.of(code).hash());
        // BR-HH-04: no column holds the plaintext
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(value -> value.contains(code));
        // 7 days (injected Clock)
        assertThat(Duration.between(((Timestamp) row.get("created_at")).toInstant(),
                ((Timestamp) row.get("expires_at")).toInstant())).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void the_code_is_never_returned_by_list_or_logged_in_the_response_of_other_calls() {
        UUID user = users.active();
        createHousehold(user);
        String code = json(post(user)).get("code").asString();

        MvcTestResult list = get(user);

        assertThat(list).hasStatus(HttpStatus.OK);
        String body = body(list);
        assertThat(body).doesNotContain(code).doesNotContain("code");
        assertThat(jsonMapper.readTree(body)).hasSize(1);
    }

    @Test
    void two_invitations_have_different_codes() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(json(post(user)).get("code").asString()).isNotEqualTo(json(post(user)).get("code").asString());
    }

    @Test
    void creating_while_one_is_pending_replaces_it() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID first = UUID.fromString(json(post(user)).get("id").asString());

        UUID second = UUID.fromString(json(post(user)).get("id").asString());

        assertThat(statusOf(first)).isEqualTo("REVOKED");
        assertThat(statusOf(second)).isEqualTo("ACTIVE");
        assertThat(activeCount(household)).isEqualTo(1);
        assertThat(jsonMapper.readTree(body(get(user))).get(0).get("id").asString()).isEqualTo(second.toString());
    }

    @Test
    void BR_HH_01_creation_is_rejected_when_the_household_has_two_members() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        addSecondMember(household, users.active());

        assertThat(post(user)).hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("INVITATION_NOT_ALLOWED");
        assertThat(activeCount(household)).isZero();
    }

    @Test
    void BR_HH_01_a_member_who_left_frees_the_seat() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID partner = users.active();
        addSecondMember(household, partner);
        jdbc.sql("UPDATE household.household_member SET left_at = now() WHERE household_id = :h AND user_id = :u")
                .param("h", household).param("u", partner).update();

        assertThat(post(user)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void BR_HH_10_dissolved_household_cannot_invite_list_or_revoke() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID invitation = UUID.fromString(json(post(user)).get("id").asString());
        dissolve(household, user);

        assertThat(post(user)).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(get(user)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(delete(user, invitation)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(statusOf(invitation)).isEqualTo("ACTIVE");
    }

    @Test
    void user_without_household_gets_404() {
        UUID user = users.active();

        assertThat(post(user)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
        assertThat(get(user)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(user, UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void creator_revokes_and_a_revoked_invitation_stays_revoked() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = UUID.fromString(json(post(user)).get("id").asString());

        assertThat(delete(user, id)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(statusOf(id)).isEqualTo("REVOKED");
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM household.invitation WHERE id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(jsonMapper.readTree(body(get(user)))).isEmpty();
        // idempotent
        assertThat(delete(user, id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(statusOf(id)).isEqualTo("REVOKED");
    }

    @Test
    void revoking_another_households_invitation_is_404_and_has_no_effect() {
        UUID owner = users.active();
        createHousehold(owner);
        UUID invitation = UUID.fromString(json(post(owner)).get("id").asString());
        UUID outsider = users.active();
        createHousehold(outsider);

        assertThat(delete(outsider, invitation)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("INVITATION_NOT_FOUND");
        assertThat(statusOf(invitation)).isEqualTo("ACTIVE");
        assertThat(jsonMapper.readTree(body(get(outsider)))).isEmpty();
    }

    @Test
    void only_the_creator_revokes_an_invitation_of_the_same_household() {
        UUID creator = users.active();
        UUID household = createHousehold(creator);
        UUID invitation = UUID.fromString(json(post(creator)).get("id").asString());
        UUID other = users.active();
        addSecondMember(household, other);

        assertThat(delete(other, invitation)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.code").isEqualTo("INVITATION_NOT_FOUND");
        assertThat(statusOf(invitation)).isEqualTo("ACTIVE");
    }

    @Test
    void redeemed_invitation_cannot_be_revoked() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = UUID.fromString(json(post(user)).get("id").asString());
        jdbc.sql("UPDATE household.invitation SET status = 'REDEEMED' WHERE id = :id").param("id", id).update();

        assertThat(delete(user, id)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.code").isEqualTo("INVITATION_NOT_REVOCABLE");
    }

    @Test
    void expired_invitation_is_not_listed() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = UUID.fromString(json(post(user)).get("id").asString());
        jdbc.sql("UPDATE household.invitation SET created_at = now() - interval '8 days', "
                + "expires_at = now() - interval '1 day' WHERE id = :id").param("id", id).update();

        assertThat(jsonMapper.readTree(body(get(user)))).isEmpty();
    }

    @Test
    void unauthenticated_requests_are_rejected() {
        assertThat(mvc.post().uri(INVITATIONS).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(INVITATIONS).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.delete().uri(INVITATIONS + "/" + UUID.randomUUID()).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_client_supplied_household_id_is_ignored() {
        UUID owner = users.active();
        UUID foreign = createHousehold(owner);
        UUID outsider = users.active();
        UUID own = createHousehold(outsider);

        MvcTestResult result = mvc.post().uri(INVITATIONS).queryParam("householdId", foreign.toString())
                .header("X-Household-Id", foreign.toString())
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(outsider))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(activeCount(foreign)).isZero();
        assertThat(activeCount(own)).isEqualTo(1);
    }

    @Test
    void creation_is_rate_limited_per_user() {
        UUID user = users.active();
        createHousehold(user);
        MvcTestResult last = null;
        for (int i = 0; i < 9; i++) {
            last = post(user);
        }

        assertThat(last).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(last.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void concurrent_creations_leave_exactly_one_active_invitation() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<MvcTestResult>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> post(user));
            }
            for (Future<MvcTestResult> future : pool.invokeAll(calls)) {
                assertThat(future.get()).hasStatus(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(activeCount(household)).isEqualTo(1);
    }

    private int activeCount(UUID household) {
        return jdbc.sql("SELECT count(*) FROM household.invitation WHERE household_id = :h AND status = 'ACTIVE'")
                .param("h", household).query(Integer.class).single();
    }

    private String statusOf(UUID invitation) {
        return jdbc.sql("SELECT status FROM household.invitation WHERE id = :id")
                .param("id", invitation).query(String.class).single();
    }

    private void addSecondMember(UUID household, UUID user) {
        jdbc.sql("""
                        INSERT INTO household.household_member (household_id, user_id, seat, joined_at)
                        VALUES (:h, :u, 2, now())
                        """).param("h", household).param("u", user).update();
    }

    private void dissolve(UUID household, UUID member) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now,
                            purge_at = :now + interval '90 days' WHERE id = :id
                        """).param("now", now).param("id", household).update();
        jdbc.sql("""
                        UPDATE household.household_member SET left_at = :now, archive_access_until = :until
                        WHERE household_id = :id AND user_id = :user
                        """)
                .param("now", now)
                .param("until", OffsetDateTime.ofInstant(Instant.now().plusSeconds(3600), ZoneOffset.UTC))
                .param("id", household).param("user", member).update();
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Notre foyer\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private MvcTestResult post(UUID user) {
        return mvc.post().uri(INVITATIONS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private MvcTestResult get(UUID user) {
        return mvc.get().uri(INVITATIONS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private MvcTestResult delete(UUID user, UUID id) {
        return mvc.delete().uri(INVITATIONS + "/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(body(result));
    }
}
