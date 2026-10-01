package com.couplefinance.household.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST /api/v1/households} — Issue #1 (BR-HH-01, BR-HH-02, BR-HH-05, BR-HH-16). */
@IntegrationTest
class CreateHouseholdIntegrationTest {

    private static final String URL = "/api/v1/households";

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
    void household_is_created_and_returned() {
        UUID user = users.active();

        assertThat(create(user, "{\"name\": \"Notre foyer\"}"))
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.id").isNotNull();
                    assertThat(json).extractingPath("$.name").isEqualTo("Notre foyer");
                    assertThat(json).extractingPath("$.status").isEqualTo("ACTIVE");
                    assertThat(json).extractingPath("$.createdAt").isNotNull();
                    assertThat(json).doesNotHavePath("$.role");
                });
        assertThat(householdCountOf(user)).isEqualTo(1);
    }

    @Test
    void creator_becomes_the_first_active_member() {
        UUID user = users.active();

        UUID householdId = createdId(create(user, "{\"name\": \"Notre foyer\"}"));

        Map<String, Object> member = jdbc.sql("""
                        SELECT user_id, seat, left_at FROM household.household_member WHERE household_id = :id
                        """)
                .param("id", householdId)
                .query()
                .singleRow();
        assertThat(member.get("user_id")).isEqualTo(user);
        assertThat(((Number) member.get("seat")).intValue()).isEqualTo(1);
        assertThat(member.get("left_at")).as("membership is active").isNull();
    }

    @Test
    void household_creator_and_audit_trail_are_recorded() {
        UUID user = users.active();

        UUID householdId = createdId(create(user, "{\"name\": \"Notre foyer\"}"));

        assertThat(jdbc.sql("SELECT created_by FROM household.household WHERE id = :id")
                .param("id", householdId).query(UUID.class).single()).isEqualTo(user);
        Map<String, Object> audit = jdbc.sql("""
                        SELECT action, actor_type, actor_user_id, changes::text AS changes
                        FROM household.audit_event WHERE household_id = :id
                        """)
                .param("id", householdId)
                .query()
                .singleRow();
        assertThat(audit).containsEntry("action", "CREATE").containsEntry("actor_type", "USER")
                .containsEntry("actor_user_id", user);
        assertThat((String) audit.get("changes")).contains("Notre foyer");
    }

    @Test
    void omitted_settings_use_the_configured_defaults() {
        assertThat(create(users.active(), "{\"name\": \"Notre foyer\"}"))
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.currency").isEqualTo("EUR");
                    assertThat(json).extractingPath("$.timezone").isEqualTo("Europe/Paris");
                    assertThat(json).extractingPath("$.periodStartDay").isEqualTo(1);
                });
    }

    @Test
    void explicit_settings_are_applied() {
        String body = """
                {"name": "Home", "currency": "TND", "timezone": "Africa/Tunis", "periodStartDay": 25}
                """;

        assertThat(create(users.active(), body))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.currency").isEqualTo("TND");
                    assertThat(json).extractingPath("$.timezone").isEqualTo("Africa/Tunis");
                    assertThat(json).extractingPath("$.periodStartDay").isEqualTo(25);
                });
    }

    @Test
    void name_is_trimmed() {
        assertThat(create(users.active(), "{\"name\": \"  Notre foyer  \"}"))
                .bodyJson()
                .extractingPath("$.name").isEqualTo("Notre foyer");
    }

    @Test
    void name_of_exactly_100_characters_is_accepted() {
        assertThat(create(users.active(), "{\"name\": \"" + "a".repeat(100) + "\"}"))
                .hasStatus(HttpStatus.CREATED);
    }

    @Nested
    class Validation {

        @Test
        void blank_name_is_rejected() {
            assertValidationError("{\"name\": \"   \"}", "name");
        }

        @Test
        void missing_name_is_rejected() {
            assertValidationError("{}", "name");
        }

        @Test
        void name_longer_than_100_characters_is_rejected() {
            assertValidationError("{\"name\": \"" + "a".repeat(101) + "\"}", "name");
        }

        @Test
        void malformed_currency_is_rejected() {
            assertValidationError("{\"name\": \"Home\", \"currency\": \"euro\"}", "currency");
        }

        @Test
        void period_start_day_outside_1_to_28_is_rejected() {
            assertValidationError("{\"name\": \"Home\", \"periodStartDay\": 29}", "periodStartDay");
            assertValidationError("{\"name\": \"Home\", \"periodStartDay\": 0}", "periodStartDay");
        }

        @Test
        void unsupported_currency_is_rejected() {
            UUID user = users.active();

            assertThat(create(user, "{\"name\": \"Home\", \"currency\": \"XXX\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code").isEqualTo("UNSUPPORTED_CURRENCY");
            assertThat(householdCountOf(user)).isZero();
        }

        @Test
        void unknown_timezone_is_rejected() {
            assertThat(create(users.active(), "{\"name\": \"Home\", \"timezone\": \"Mars/Olympus\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code").isEqualTo("INVALID_TIMEZONE");
        }

        @Test
        void a_client_supplied_owner_or_user_id_is_rejected() {
            UUID user = users.active();
            UUID someoneElse = users.active();

            assertThat(create(user, "{\"name\": \"Home\", \"ownerId\": \"" + someoneElse + "\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
            assertThat(create(user, "{\"name\": \"Home\", \"userId\": \"" + someoneElse + "\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(householdCountOf(user)).isZero();
            assertThat(householdCountOf(someoneElse)).isZero();
        }

        private void assertValidationError(String body, String field) {
            UUID user = users.active();
            assertThat(create(user, body))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .satisfies(json -> {
                        assertThat(json).extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
                        assertThat(json).extractingPath("$.errors[0].field").isEqualTo(field);
                    });
            assertThat(householdCountOf(user)).isZero();
        }
    }

    @Nested
    class Authentication {

        @Test
        void unauthenticated_request_is_rejected() {
            assertUnauthorized(mvc.post().uri(URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"Home\"}")
                    .exchange());
        }

        @Test
        void expired_token_is_rejected() {
            UUID user = users.active();
            String header = tokens.bearer(user, claims -> claims
                    .issueTime(Date.from(Instant.now().minusSeconds(3600)))
                    .expirationTime(Date.from(Instant.now().minusSeconds(600))));

            assertUnauthorized(post(header, "{\"name\": \"Home\"}"));
            assertThat(householdCountOf(user)).isZero();
        }

        @Test
        void token_without_expiry_is_rejected() {
            assertUnauthorized(post(tokens.bearer(users.active(), claims -> claims.expirationTime(null)),
                    "{\"name\": \"Home\"}"));
        }

        @Test
        void token_for_another_audience_is_rejected() {
            assertUnauthorized(post(tokens.bearer(users.active(), claims -> claims.audience("another-api")),
                    "{\"name\": \"Home\"}"));
        }

        @Test
        void token_signed_by_an_untrusted_key_is_rejected() {
            assertUnauthorized(post(tokens.bearerSignedByUnknownKey(users.active()), "{\"name\": \"Home\"}"));
        }

        @Test
        void token_of_an_unknown_user_is_rejected() {
            assertUnauthorized(post(tokens.bearer(UUID.randomUUID()), "{\"name\": \"Home\"}"));
        }

        @Test
        void user_with_unverified_email_is_forbidden() {
            UUID user = users.pendingVerification();

            assertThat(create(user, "{\"name\": \"Home\"}"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .bodyJson()
                    .extractingPath("$.code").isEqualTo("EMAIL_NOT_VERIFIED");
            assertThat(householdCountOf(user)).isZero();
        }

        private void assertUnauthorized(MvcTestResult result) {
            assertThat(result)
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void BR_HH_02_user_with_an_active_household_cannot_create_another_one() {
        UUID user = users.active();
        assertThat(create(user, "{\"name\": \"First\"}")).hasStatus(HttpStatus.CREATED);

        assertThat(create(user, "{\"name\": \"Second\"}"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("ALREADY_IN_HOUSEHOLD");
        assertThat(householdCountOf(user)).isEqualTo(1);
    }

    @Test
    void households_of_different_users_are_independent() {
        UUID first = users.active();
        UUID second = users.active();

        assertThat(create(first, "{\"name\": \"First\"}")).hasStatus(HttpStatus.CREATED);
        assertThat(create(second, "{\"name\": \"Second\"}")).hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult create(UUID user, String body) {
        return post(tokens.bearer(user), body);
    }

    private MvcTestResult post(String authorization, String body) {
        return mvc.post().uri(URL)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private UUID createdId(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String json = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return UUID.fromString(jsonMapper.readTree(json).get("id").asString());
    }

    private long householdCountOf(UUID user) {
        return jdbc.sql("SELECT count(*) FROM household.household WHERE created_by = :user")
                .param("user", user)
                .query(Long.class)
                .single();
    }
}
