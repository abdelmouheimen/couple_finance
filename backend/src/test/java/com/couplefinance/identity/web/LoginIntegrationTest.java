package com.couplefinance.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.shared.ratelimit.RateLimiter;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST /api/v1/auth/login} and {@code GET /api/v1/me} — Issue #75 (security.md §3, BR-HH-16). */
@IntegrationTest
class LoginIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/me";
    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    RateLimiter rateLimiter;

    private static String freshEmail() {
        return "login-" + UUID.randomUUID() + "@example.test";
    }

    private MvcTestResult login(String email, String password) {
        return loginBody(json.writeValueAsString(Map.of("email", email, "password", password)));
    }

    private MvcTestResult loginBody(String body) {
        return mvc.post().uri(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<?, ?> loginOk(String email, String password) {
        MvcTestResult result = login(email, password);
        assertThat(result).hasStatusOk();
        return json.readValue(text(result), Map.class);
    }

    @Test
    void valid_credentials_issue_a_token_accepted_by_the_validator_on_a_protected_endpoint() {
        String email = freshEmail();
        UUID user = users.active(email, PASSWORD);

        MvcTestResult result = login(email, PASSWORD);

        assertThat(result).hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson().satisfies(body -> {
                    assertThat(body).extractingPath("$.tokenType").isEqualTo("Bearer");
                    assertThat(body).extractingPath("$.expiresIn").isEqualTo(900);
                });
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        Map<?, ?> tokensBody = json.readValue(text(result), Map.class);
        String accessToken = (String) tokensBody.get("accessToken");

        assertThat(mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).exchange())
                .hasStatusOk()
                .bodyJson().satisfies(me -> {
                    assertThat(me).extractingPath("$.id").isEqualTo(user.toString());
                    assertThat(me).extractingPath("$.email").isEqualTo(email);
                    assertThat(me).extractingPath("$.displayName").isEqualTo("Test user");
                    assertThat(me).extractingPath("$.locale").isEqualTo("fr-FR");
                    assertThat(me).extractingPath("$.status").isEqualTo("ACTIVE");
                    assertThat(me).doesNotHavePath("$.passwordHash");
                });
    }

    @Test
    void access_token_claims_are_sub_sid_iat_exp_aud_without_household_and_the_session_exists() throws Exception {
        String email = freshEmail();
        UUID user = users.active(email, PASSWORD);

        String accessToken = (String) loginOk(email, PASSWORD).get("accessToken");

        JWTClaimsSet claims = SignedJWT.parse(accessToken).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(user.toString());
        assertThat(claims.getAudience()).containsExactly(TestTokens.AUDIENCE);
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(claims.getClaims().keySet()).doesNotContain("household_id", "householdId", "hid");
        UUID sid = UUID.fromString(claims.getStringClaim("sid"));
        assertThat(jdbc.sql("SELECT user_id FROM identity.session WHERE id = :id").param("id", sid)
                .query(UUID.class).single()).isEqualTo(user);
    }

    @Test
    void only_the_sha256_of_the_refresh_token_is_stored_and_it_expires_in_30_days() throws Exception {
        String email = freshEmail();
        UUID user = users.active(email, PASSWORD);

        Map<?, ?> body = loginOk(email, PASSWORD);

        String refresh = (String) body.get("refreshToken");
        assertThat(java.util.Base64.getUrlDecoder().decode(refresh)).hasSize(32);
        Map<String, Object> row = jdbc.sql("""
                        SELECT r.token_hash, r.issued_at, r.expires_at
                        FROM identity.refresh_token r JOIN identity.session s ON s.id = r.session_id
                        WHERE s.user_id = :user
                        """).param("user", user).query().singleRow();
        assertThat((byte[]) row.get("token_hash")).isEqualTo(
                MessageDigest.getInstance("SHA-256").digest(refresh.getBytes(StandardCharsets.UTF_8)));
        Instant issued = ((java.sql.Timestamp) row.get("issued_at")).toInstant();
        Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        assertThat(Duration.between(issued, expires)).isEqualTo(Duration.ofDays(30));
        // The plaintext secret is nowhere in the identity tables.
        Integer occurrences = jdbc.sql("""
                        SELECT count(*) FROM identity.session s
                        WHERE s.device_label = :secret OR s.id::text = :secret
                        """).param("secret", refresh).query(Integer.class).single();
        assertThat(occurrences).isZero();
    }

    @Test
    void device_label_is_stored_on_the_session() {
        String email = freshEmail();
        UUID user = users.active(email, PASSWORD);

        MvcTestResult result = loginBody(json.writeValueAsString(
                Map.of("email", email, "password", PASSWORD, "deviceLabel", "Alex's phone")));

        assertThat(result).hasStatusOk();
        assertThat(jdbc.sql("SELECT device_label FROM identity.session WHERE user_id = :user")
                .param("user", user).query(String.class).single()).isEqualTo("Alex's phone");
    }

    @Test
    void email_is_matched_case_insensitively() {
        String email = freshEmail();
        users.active(email, PASSWORD);

        assertThat(login(email.toUpperCase(java.util.Locale.ROOT), PASSWORD)).hasStatusOk();
    }

    @Test
    void pending_verification_account_logs_in_but_is_refused_on_protected_endpoints() {
        String email = freshEmail();
        users.pendingVerification(email, PASSWORD);

        String accessToken = (String) loginOk(email, PASSWORD).get("accessToken");

        assertThat(mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).exchange())
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void wrong_password_unknown_email_and_deleted_account_are_indistinguishable() {
        String knownEmail = freshEmail();
        users.active(knownEmail, PASSWORD);
        users.deleted();

        MvcTestResult wrongPassword = login(knownEmail, "not-the-password");
        MvcTestResult unknownEmail = login(freshEmail(), "not-the-password");
        MvcTestResult deletedAccount = login(freshEmail(), PASSWORD);

        assertThat(wrongPassword).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        String expectedBody = normalised(wrongPassword);
        assertThat(normalised(unknownEmail)).isEqualTo(expectedBody);
        assertThat(normalised(deletedAccount)).isEqualTo(expectedBody);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(wrongPassword.getResponse().getStatus());
        assertThat(deletedAccount.getResponse().getStatus()).isEqualTo(wrongPassword.getResponse().getStatus());
    }

    @Test
    void a_failed_login_creates_no_session() {
        String email = freshEmail();
        UUID user = users.active(email, PASSWORD);

        assertThat(login(email, "not-the-password")).hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(jdbc.sql("SELECT count(*) FROM identity.session WHERE user_id = :user").param("user", user)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void password_hash_of_another_encoder_family_is_rejected() {
        String email = freshEmail();
        users.activeWithRawHash(email, new BCryptPasswordEncoder().encode(PASSWORD));

        assertThat(login(email, PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void account_rate_limit_returns_429_problem_details_after_the_budget() {
        String email = freshEmail();
        users.active(email, PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "not-the-password")).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        MvcTestResult limited = login(email, PASSWORD);

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
        // Another account is unaffected.
        String other = freshEmail();
        users.active(other, PASSWORD);
        assertThat(login(other, PASSWORD)).hasStatusOk();
    }

    @Test
    void authentication_routes_use_the_auth_rate_limit_profile() {
        assertThat(rateLimiter.profileFor(LOGIN)).isEqualTo("auth");
    }

    @Test
    void invalid_requests_are_rejected_with_validation_errors() {
        assertThat(loginBody("{\"email\":\"not-an-email\",\"password\":\"x\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(loginBody("{\"email\":\"a@example.test\",\"password\":\"" + "p".repeat(129) + "\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(loginBody("{\"email\":\"a@example.test\",\"password\":\"x\",\"deviceLabel\":\""
                + "d".repeat(81) + "\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(loginBody("{\"email\":\"a@example.test\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blank_or_control_character_device_labels_are_validation_errors_not_server_errors() {
        assertThat(loginBody("{\"email\":\"a@example.test\",\"password\":\"x\",\"deviceLabel\":\"   \"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(loginBody("{\"email\":\"a@example.test\",\"password\":\"x\",\"deviceLabel\":\"a\\nb\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void a_client_supplied_user_id_is_rejected() {
        assertThat(loginBody("{\"email\":\"a@example.test\",\"password\":\"x\",\"userId\":\""
                + UUID.randomUUID() + "\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oversized_body_is_rejected_before_being_read() {
        String body = "{\"email\":\"a@example.test\",\"password\":\"" + "p".repeat(5000) + "\"}";

        assertThat(loginBody(body)).hasStatus(HttpStatus.CONTENT_TOO_LARGE)
                .bodyJson().extractingPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void login_does_not_echo_credentials_in_errors() {
        MvcTestResult result = login(freshEmail(), "super-secret-password-value");

        assertThat(text(result)).doesNotContain("super-secret-password-value");
    }

    @Test
    void me_requires_authentication() {
        assertThat(mvc.get().uri(ME).exchange()).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void me_returns_the_identity_of_the_token_subject_only() {
        UUID user = users.active();
        UUID other = users.active();

        assertThat(mvc.get().uri(ME + "?userId=" + other)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange())
                .hasStatusOk().bodyJson().extractingPath("$.id").isEqualTo(user.toString());
    }

    // ---------------------------------------------------------------- database constraints

    @Test
    void refresh_token_hash_must_be_32_bytes_and_unique_and_sessions_need_a_consistent_revocation() {
        UUID user = users.active();
        UUID session = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.session (id, user_id, created_at, last_used_at) VALUES (:id, :u, now(), now())")
                .param("id", session).param("u", user).update();

        assertThatThrownBy(() -> insertToken(session, new byte[31])).isInstanceOf(DataIntegrityViolationException.class);
        insertToken(session, new byte[32]);
        assertThatThrownBy(() -> insertToken(session, new byte[32])).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.session SET revoked_at = now() WHERE id = :id")
                .param("id", session).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.session SET revoked_at = now(), revoke_reason = 'BOGUS' "
                + "WHERE id = :id").param("id", session).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.session SET device_label = :l WHERE id = :id")
                .param("l", "d".repeat(81)).param("id", session).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertToken(UUID session, byte[] hash) {
        jdbc.sql("""
                        INSERT INTO identity.refresh_token (id, session_id, token_hash, issued_at, expires_at)
                        VALUES (:id, :s, :h, now(), now() + interval '1 day')
                        """)
                .param("id", UUID.randomUUID()).param("s", session).param("h", hash).update();
    }

    /** Body with the per-request, non-semantic members removed. */
    private String normalised(MvcTestResult result) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = new java.util.TreeMap<>(
                    json.readValue(text(result), Map.class));
            body.remove("traceId");
            return body.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
