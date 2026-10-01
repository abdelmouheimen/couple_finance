package com.couplefinance.household.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/households/me} and the {@link CurrentHousehold} facade — Issue #8
 * (BR-HH-02, BR-HH-03, BR-HH-10).
 */
@IntegrationTest
class CurrentHouseholdIntegrationTest {

    private static final String ME = "/api/v1/households/me";

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
    CurrentHousehold currentHousehold;

    @Test
    void member_gets_their_household() {
        UUID user = users.active();
        UUID household = createHousehold(user, "Notre foyer");

        assertThat(get(user))
                .hasStatus(HttpStatus.OK)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.id").isEqualTo(household.toString());
                    assertThat(json).extractingPath("$.name").isEqualTo("Notre foyer");
                    assertThat(json).extractingPath("$.currency").isEqualTo("EUR");
                    assertThat(json).extractingPath("$.timezone").isEqualTo("Europe/Paris");
                    assertThat(json).extractingPath("$.periodStartDay").isEqualTo(1);
                    assertThat(json).extractingPath("$.status").isEqualTo("ACTIVE");
                    assertThat(json).extractingPath("$.createdAt").isNotNull();
                    assertThat(json).doesNotHavePath("$.role");
                    assertThat(json).doesNotHavePath("$.archiveAccessUntil");
                    assertThat(json).doesNotHavePath("$.members");
                });
    }

    @Test
    void user_without_household_gets_404() {
        assertThat(get(users.active()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
    }

    @Test
    void a_user_never_sees_the_household_of_another_user() {
        UUID owner = users.active();
        UUID outsider = users.active();
        createHousehold(owner, "Private foyer");
        UUID outsiderHousehold = createHousehold(outsider, "Other foyer");

        assertThat(get(outsider)).bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.id").isEqualTo(outsiderHousehold.toString());
            assertThat(json).extractingPath("$.name").isEqualTo("Other foyer");
        });
    }

    @Test
    void a_client_supplied_household_id_is_ignored() {
        UUID owner = users.active();
        UUID outsider = users.active();
        UUID foreign = createHousehold(owner, "Private foyer");

        assertThat(mvc.get().uri(ME).queryParam("householdId", foreign.toString())
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(outsider))
                .header("X-Household-Id", foreign.toString())
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void unauthenticated_request_is_rejected() {
        assertThat(mvc.get().uri(ME).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void user_with_unverified_email_is_forbidden() {
        assertThat(get(users.pendingVerification()))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void BR_HH_10_archive_reader_gets_the_dissolved_household() {
        UUID user = users.active();
        UUID household = createHousehold(user, "Old foyer");
        dissolve(household, user, Instant.now().plusSeconds(3600));

        assertThat(get(user))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.id").isEqualTo(household.toString());
                    assertThat(json).extractingPath("$.status").isEqualTo("DISSOLVED");
                });
    }

    @Test
    void BR_HH_10_expired_archive_access_gets_404() {
        UUID user = users.active();
        UUID household = createHousehold(user, "Old foyer");
        dissolve(household, user, Instant.now().minusSeconds(60));

        assertThat(get(user)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_HH_02_new_active_household_takes_precedence_over_an_archive() {
        UUID user = users.active();
        UUID old = createHousehold(user, "Old foyer");
        dissolve(old, user, Instant.now().plusSeconds(3600));
        UUID fresh = createHousehold(user, "New foyer");

        assertThat(get(user)).bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.id").isEqualTo(fresh.toString());
            assertThat(json).extractingPath("$.status").isEqualTo("ACTIVE");
        });
    }

    @Test
    void deleted_household_is_not_returned() {
        UUID user = users.active();
        UUID household = createHousehold(user, "Gone foyer");
        jdbc.sql("UPDATE household.household SET status = 'DELETED', dissolved_at = now() WHERE id = :id")
                .param("id", household).update();

        assertThat(get(user)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_HH_10_dissolved_household_without_archive_window_is_not_reachable_as_active_member() {
        UUID user = users.active();
        UUID household = createHousehold(user, "Odd foyer");
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = now(), "
                + "purge_at = now() + interval '90 days' WHERE id = :id").param("id", household).update();

        assertThat(get(user)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void facade_resolves_a_member_context_of_an_active_household() {
        UUID user = users.active();
        UUID household = createHousehold(user, "Notre foyer");

        HouseholdContext context = asUser(user, currentHousehold::currentHousehold);

        assertThat(context.householdId().value()).isEqualTo(household);
        assertThat(context.userId().value()).isEqualTo(user);
        assertThat(context.status()).isEqualTo(HouseholdContext.Status.ACTIVE);
        assertThat(context.role()).isEqualTo(HouseholdContext.Role.MEMBER);
        context.requireWritable();
    }

    @Test
    void BR_HH_10_facade_resolves_an_archive_reader_and_rejects_writes() {
        UUID user = users.active();
        UUID household = createHousehold(user, "Old foyer");
        dissolve(household, user, Instant.now().plusSeconds(3600));

        HouseholdContext context = asUser(user, currentHousehold::currentHousehold);

        assertThat(context.status()).isEqualTo(HouseholdContext.Status.DISSOLVED);
        assertThat(context.role()).isEqualTo(HouseholdContext.Role.ARCHIVE_READER);
        assertThatThrownBy(context::requireWritable)
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("HOUSEHOLD_READ_ONLY"));
    }

    @Test
    void facade_reports_no_household_as_empty_or_not_found() {
        UUID user = users.active();

        assertThat(asUser(user, currentHousehold::findCurrentHousehold)).isEmpty();
        assertThatThrownBy(() -> asUser(user, currentHousehold::currentHousehold))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("HOUSEHOLD_NOT_FOUND"));
    }

    private <T> T asUser(UUID user, java.util.function.Supplier<T> action) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void dissolve(UUID household, UUID member, Instant archiveUntil) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now,
                            purge_at = :now + interval '90 days' WHERE id = :id
                        """)
                .param("now", now).param("id", household).update();
        jdbc.sql("""
                        UPDATE household.household_member SET left_at = :now, archive_access_until = :until
                        WHERE household_id = :id AND user_id = :user
                        """)
                .param("now", now)
                .param("until", OffsetDateTime.ofInstant(archiveUntil, ZoneOffset.UTC))
                .param("id", household).param("user", member).update();
    }

    private UUID createHousehold(UUID user, String name) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + name + "\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String json = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return UUID.fromString(jsonMapper.readTree(json).get("id").asString());
    }

    private MvcTestResult get(UUID user) {
        return mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }
}
