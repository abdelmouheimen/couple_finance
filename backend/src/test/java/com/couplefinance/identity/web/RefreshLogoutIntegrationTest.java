package com.couplefinance.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import com.nimbusds.jwt.SignedJWT;
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
 * Refresh-token rotation, grace window, reuse detection and logout — Issue #76 (security.md §3, ADR-007 option A).
 */
@IntegrationTest
class RefreshLogoutIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String LOGOUT_ALL = "/api/v1/auth/logout-all";
    private static final String ME = "/api/v1/me";
    private static final String PASSWORD = "a-long-enough-password";

    private record Tokens(String access, String refresh) {
        UUID sessionId() {
            try {
                return UUID.fromString(SignedJWT.parse(access).getJWTClaimsSet().getStringClaim("sid"));
            } catch (java.text.ParseException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens testTokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    private Tokens login(String email) {
        MvcTestResult result = mvc.post().uri(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))).exchange();
        assertThat(result).hasStatusOk();
        return tokens(result);
    }

    private Tokens tokens(MvcTestResult result) {
        Map<?, ?> body = json.readValue(text(result), Map.class);
        return new Tokens((String) body.get("accessToken"), (String) body.get("refreshToken"));
    }

    private static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcTestResult refresh(String refreshToken) {
        return mvc.post().uri(REFRESH).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))).exchange();
    }

    private MvcTestResult post(String uri, String accessToken) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).exchange();
    }

    private static byte[] hash(String secret) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String freshEmail() {
        return "refresh-" + UUID.randomUUID() + "@example.test";
    }

    private void assertInvalidRefreshToken(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN");
    }

    private void ageRotation(UUID session, Duration age) {
        jdbc.sql("UPDATE identity.refresh_token SET rotated_at = rotated_at - make_interval(secs => :s) "
                + "WHERE session_id = :id AND rotated_at IS NOT NULL")
                .param("s", (double) age.toSeconds()).param("id", session).update();
    }

    private int validHeads(UUID session) {
        return jdbc.sql("""
                SELECT count(*) FROM identity.refresh_token t JOIN identity.session s ON s.id = t.session_id
                WHERE t.session_id = :id AND t.rotated_at IS NULL AND t.revoked_at IS NULL AND s.revoked_at IS NULL
                """).param("id", session).query(Integer.class).single();
    }

    private Map<String, Object> sessionRow(UUID session) {
        return jdbc.sql("SELECT revoked_at, revoke_reason, last_used_at FROM identity.session WHERE id = :id")
                .param("id", session).query().singleRow();
    }

    @Test
    void login_refresh_logout_cycle() throws Exception {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens first = login(email);

        MvcTestResult refreshed = refresh(first.refresh());

        assertThat(refreshed).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.tokenType").isEqualTo("Bearer");
            assertThat(body).extractingPath("$.expiresIn").isEqualTo(900);
        });
        assertThat(refreshed.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        Tokens second = tokens(refreshed);
        assertThat(second.refresh()).isNotEqualTo(first.refresh());
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(mvc.get().uri(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + second.access()).exchange())
                .hasStatusOk();

        // The old token is marked rotated and points at its successor; the successor expires in 30 days.
        Map<String, Object> old = jdbc.sql("SELECT rotated_at, replaced_by_id FROM identity.refresh_token "
                + "WHERE token_hash = :h").param("h", hash(first.refresh())).query().singleRow();
        Map<String, Object> successor = jdbc.sql("SELECT id, issued_at, expires_at FROM identity.refresh_token "
                + "WHERE token_hash = :h").param("h", hash(second.refresh())).query().singleRow();
        assertThat(old.get("rotated_at")).isNotNull();
        assertThat(old.get("replaced_by_id")).isEqualTo(successor.get("id"));
        assertThat(Duration.between(((java.sql.Timestamp) successor.get("issued_at")).toInstant(),
                ((java.sql.Timestamp) successor.get("expires_at")).toInstant())).isEqualTo(Duration.ofDays(30));

        assertThat(post(LOGOUT, second.access())).hasStatus(HttpStatus.NO_CONTENT);

        assertInvalidRefreshToken(refresh(second.refresh()));
        assertThat(sessionRow(first.sessionId()).get("revoke_reason")).isEqualTo("LOGOUT");
        // Idempotent.
        assertThat(post(LOGOUT, second.access())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(sessionRow(first.sessionId()).get("revoke_reason")).isEqualTo("LOGOUT");
    }

    @Test
    void refresh_updates_the_session_last_used_at() {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens first = login(email);
        jdbc.sql("UPDATE identity.session SET last_used_at = last_used_at - interval '1 hour' WHERE id = :id")
                .param("id", first.sessionId()).update();
        Instant before = ((java.sql.Timestamp) sessionRow(first.sessionId()).get("last_used_at")).toInstant();

        assertThat(refresh(first.refresh())).hasStatusOk();

        Instant after = ((java.sql.Timestamp) sessionRow(first.sessionId()).get("last_used_at")).toInstant();
        assertThat(after).isAfter(before.plus(Duration.ofMinutes(59)));
    }

    @Test
    void the_old_token_within_the_grace_window_rotates_forward_and_only_the_newest_stays_valid() throws Exception {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens first = login(email);
        Tokens second = tokens(refresh(first.refresh()));

        MvcTestResult again = refresh(first.refresh());

        assertThat(again).hasStatusOk();
        Tokens third = tokens(again);
        assertThat(third.refresh()).isNotIn(first.refresh(), second.refresh());
        assertThat(sessionRow(first.sessionId()).get("revoked_at")).isNull();
        assertThat(validHeads(first.sessionId())).isEqualTo(1);
        // The unreleased successor (second) is rotated forward too: it is no longer the head of the chain.
        Map<String, Object> secondRow = jdbc.sql("SELECT rotated_at, replaced_by_id FROM identity.refresh_token "
                + "WHERE token_hash = :h").param("h", hash(second.refresh())).query().singleRow();
        UUID thirdId = jdbc.sql("SELECT id FROM identity.refresh_token WHERE token_hash = :h")
                .param("h", hash(third.refresh())).query(UUID.class).single();
        assertThat(secondRow.get("replaced_by_id")).isEqualTo(thirdId);
        // The single chain: exactly 3 tokens, no branch.
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE session_id = :id")
                .param("id", first.sessionId()).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void the_old_token_after_the_grace_window_revokes_the_session_and_the_successor_stops_working() {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens first = login(email);
        Tokens second = tokens(refresh(first.refresh()));
        ageRotation(first.sessionId(), Duration.ofSeconds(31));

        assertInvalidRefreshToken(refresh(first.refresh()));

        Map<String, Object> session = sessionRow(first.sessionId());
        assertThat(session.get("revoke_reason")).isEqualTo("REUSE_DETECTED");
        assertThat(session.get("revoked_at")).isNotNull();
        assertInvalidRefreshToken(refresh(second.refresh()));
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE session_id = :id "
                + "AND revoked_at IS NULL").param("id", first.sessionId()).query(Integer.class).single()).isZero();
    }

    @Test
    void unknown_expired_revoked_and_replayed_tokens_get_the_same_problem() {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens expired = login(email);
        jdbc.sql("UPDATE identity.refresh_token SET issued_at = issued_at - interval '31 days', "
                + "expires_at = expires_at - interval '31 days' WHERE session_id = :id")
                .param("id", expired.sessionId()).update();
        Tokens revoked = login(email);
        post(LOGOUT, revoked.access());
        Tokens replayed = login(email);
        refresh(replayed.refresh());
        ageRotation(replayed.sessionId(), Duration.ofMinutes(1));

        MvcTestResult unknown = refresh("A".repeat(43));
        List<MvcTestResult> all = List.of(unknown, refresh(expired.refresh()), refresh(revoked.refresh()),
                refresh(replayed.refresh()));

        for (MvcTestResult result : all) {
            assertInvalidRefreshToken(result);
            assertThat(normalised(result)).isEqualTo(normalised(unknown));
        }
    }

    private String normalised(MvcTestResult result) {
        Map<String, Object> body = new java.util.TreeMap<>(
                json.readValue(text(result), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { }));
        body.remove("traceId");
        return body.toString();
    }

    @Test
    void a_deleted_account_cannot_refresh() {
        String email = freshEmail();
        UUID user = users.active(email, PASSWORD);
        Tokens tokens = login(email);
        jdbc.sql("UPDATE identity.user_account SET status = 'DELETED', deleted_at = now(), email = NULL, password_hash = NULL, display_name = NULL WHERE id = :id").param("id", user).update();

        assertInvalidRefreshToken(refresh(tokens.refresh()));
    }

    @Test
    void logout_revokes_only_that_session_and_logout_all_revokes_every_session() {
        String email = freshEmail();
        UUID user = users.active(email, PASSWORD);
        Tokens phone = login(email);
        Tokens tablet = login(email);
        Tokens other = login(email);

        assertThat(post(LOGOUT, phone.access())).hasStatus(HttpStatus.NO_CONTENT);

        assertInvalidRefreshToken(refresh(phone.refresh()));
        assertThat(refresh(tablet.refresh())).hasStatusOk();

        assertThat(post(LOGOUT_ALL, other.access())).hasStatus(HttpStatus.NO_CONTENT);

        assertInvalidRefreshToken(refresh(tablet.refresh()));
        assertInvalidRefreshToken(refresh(other.refresh()));
        assertThat(jdbc.sql("SELECT revoke_reason FROM identity.session WHERE user_id = :u ORDER BY created_at")
                .param("u", user).query(String.class).list()).containsExactly("LOGOUT", "LOGOUT_ALL", "LOGOUT_ALL");
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token t JOIN identity.session s "
                + "ON s.id = t.session_id WHERE s.user_id = :u AND t.revoked_at IS NULL")
                .param("u", user).query(Integer.class).single()).isZero();
    }

    @Test
    void logout_all_does_not_touch_another_users_sessions() {
        String mine = freshEmail();
        String theirs = freshEmail();
        users.active(mine, PASSWORD);
        users.active(theirs, PASSWORD);
        Tokens me = login(mine);
        Tokens them = login(theirs);

        assertThat(post(LOGOUT_ALL, me.access())).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(refresh(them.refresh())).hasStatusOk();
    }

    @Test
    void a_user_cannot_revoke_another_users_session_with_a_foreign_sid() {
        String victimEmail = freshEmail();
        users.active(victimEmail, PASSWORD);
        Tokens victim = login(victimEmail);
        UUID attacker = users.active();
        String forged = testTokens.bearer(attacker, claims -> claims.claim("sid", victim.sessionId().toString()));

        assertThat(mvc.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, forged).exchange())
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(sessionRow(victim.sessionId()).get("revoked_at")).isNull();
        assertThat(refresh(victim.refresh())).hasStatusOk();
    }

    @Test
    void logout_and_logout_all_require_authentication() {
        assertThat(mvc.post().uri(LOGOUT).exchange()).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(mvc.post().uri(LOGOUT_ALL).exchange()).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void the_refresh_token_is_never_stored_in_plaintext() throws Exception {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens tokens = login(email);
        Tokens next = tokens(refresh(tokens.refresh()));

        // Only 32-byte hashes exist; no text column of the identity tables holds either secret.
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE session_id = :id "
                + "AND octet_length(token_hash) = 32").param("id", tokens.sessionId())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE token_hash = :a OR token_hash = :b")
                .param("a", tokens.refresh().getBytes(StandardCharsets.UTF_8))
                .param("b", next.refresh().getBytes(StandardCharsets.UTF_8)).query(Integer.class).single()).isZero();
    }

    @Test
    void invalid_bodies_are_validation_errors_and_a_client_user_id_is_rejected() {
        assertThat(mvc.post().uri(REFRESH).contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(mvc.post().uri(REFRESH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + "x".repeat(257) + "\"}").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri(REFRESH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"abc\",\"userId\":\"" + UUID.randomUUID() + "\"}").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void the_refresh_budget_is_enforced_per_session_with_429() {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens current = login(email);
        for (int i = 0; i < 25; i++) {
            MvcTestResult ok = refresh(current.refresh());
            assertThat(ok).hasStatusOk();
            current = tokens(ok);
        }

        MvcTestResult limited = refresh(current.refresh());

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS).bodyJson().extractingPath("$.code")
                .isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
    }

    // ---------------------------------------------------------------- concurrency (CLAUDE.md 9.4)

    private List<MvcTestResult> parallelRefreshes(String refreshToken, int n) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<MvcTestResult> call = () -> {
                    start.await();
                    return refresh(refreshToken);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallel_refreshes_with_the_same_token_leave_one_valid_token_and_never_revoke_the_session()
            throws Exception {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens first = login(email);

        List<MvcTestResult> results = parallelRefreshes(first.refresh(), 8);

        List<String> issued = new ArrayList<>();
        for (MvcTestResult result : results) {
            assertThat(result).hasStatusOk();
            issued.add(tokens(result).refresh());
        }
        assertThat(issued).doesNotHaveDuplicates();
        assertThat(sessionRow(first.sessionId()).get("revoked_at")).isNull();
        assertThat(validHeads(first.sessionId())).isEqualTo(1);
        // Exactly one of the returned tokens is the head and is still accepted; the others are rotated forward.
        long headMatches = 0;
        for (String token : issued) {
            headMatches += jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE token_hash = :h "
                    + "AND rotated_at IS NULL").param("h", hash(token)).query(Integer.class).single();
        }
        assertThat(headMatches).isEqualTo(1);
        // Single chain: n + 1 tokens, every one but the head replaced by another token of the same session.
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE session_id = :id")
                .param("id", first.sessionId()).query(Integer.class).single()).isEqualTo(9);
    }

    @Test
    void parallel_refreshes_spanning_the_grace_boundary_never_leave_two_valid_successors() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            String email = freshEmail();
            users.active(email, PASSWORD);
            Tokens first = login(email);
            tokens(refresh(first.refresh()));
            // The rotation of the first token is about to leave the grace window.
            ageRotation(first.sessionId(), Duration.ofMillis(29_950));

            List<MvcTestResult> results = parallelRefreshes(first.refresh(), 8);

            for (MvcTestResult result : results) {
                assertThat(result.getResponse().getStatus()).isIn(200, 401);
            }
            assertThat(validHeads(first.sessionId())).isLessThanOrEqualTo(1);
            if (sessionRow(first.sessionId()).get("revoked_at") != null) {
                assertThat(sessionRow(first.sessionId()).get("revoke_reason")).isEqualTo("REUSE_DETECTED");
                assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE session_id = :id "
                        + "AND revoked_at IS NULL").param("id", first.sessionId()).query(Integer.class).single())
                        .isZero();
            }
        }
    }

    @Test
    void parallel_logout_and_refresh_never_leave_a_valid_token() throws Exception {
        String email = freshEmail();
        users.active(email, PASSWORD);
        Tokens first = login(email);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<MvcTestResult> logout = pool.submit(() -> {
                start.await();
                return post(LOGOUT, first.access());
            });
            Future<MvcTestResult> refresh = pool.submit(() -> {
                start.await();
                return refresh(first.refresh());
            });
            start.countDown();
            assertThat(logout.get()).hasStatus(HttpStatus.NO_CONTENT);
            assertThat(refresh.get().getResponse().getStatus()).isIn(200, 401);
        } finally {
            pool.shutdownNow();
        }

        assertThat(validHeads(first.sessionId())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE session_id = :id "
                + "AND revoked_at IS NULL").param("id", first.sessionId()).query(Integer.class).single()).isZero();
    }
}
