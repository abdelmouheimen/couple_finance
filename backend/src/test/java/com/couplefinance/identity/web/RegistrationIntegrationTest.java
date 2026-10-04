package com.couplefinance.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.couplefinance.identity.application.EmailMessage;
import com.couplefinance.identity.infrastructure.InMemoryEmailDelivery;
import com.couplefinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** Registration, email verification and resend — Issue #77 (security.md section 3, BR-HH-16). */
@IntegrationTest
class RegistrationIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String VERIFY = "/api/v1/auth/verify-email";
    private static final String RESEND = "/api/v1/auth/resend-verification";
    private static final String PASSWORD = "a-long-enough-password";
    private static final String VERIFICATION_MARKER = "verification code";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    InMemoryEmailDelivery mailbox;

    private static String freshEmail() {
        return "reg-" + UUID.randomUUID() + "@example.test";
    }

    private MvcTestResult post(String uri, Map<String, ?> body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)).exchange();
    }

    private MvcTestResult register(String email) {
        return post(REGISTER, Map.of("email", email, "password", PASSWORD, "displayName", "Alex"));
    }

    private static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The token embedded in the latest verification mail sent to the address. */
    private String tokenSentTo(String email) {
        List<EmailMessage> mails = mailbox.sentTo(email).stream()
                .filter(m -> m.body().contains(VERIFICATION_MARKER)).toList();
        String body = mails.get(mails.size() - 1).body();
        return body.substring(body.indexOf(": ") + 2).lines().findFirst().orElseThrow();
    }

    private int accountCount(String email) {
        return jdbc.sql("SELECT count(*) FROM identity.user_account WHERE lower(email) = lower(:e)")
                .param("e", email).query(Integer.class).single();
    }

    private String statusOf(String email) {
        return jdbc.sql("SELECT status FROM identity.user_account WHERE lower(email) = lower(:e)")
                .param("e", email).query(String.class).single();
    }

    @Test
    void register_creates_a_pending_account_with_an_argon2id_hash_and_sends_one_verification_mail() {
        String email = freshEmail();

        MvcTestResult result = register(email);

        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        assertThat(text(result)).isEmpty();
        Map<String, Object> row = jdbc.sql("""
                        SELECT status, password_hash, email_verified_at, locale, display_name
                        FROM identity.user_account WHERE email = :e
                        """).param("e", email).query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat((String) row.get("password_hash")).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(row.get("email_verified_at")).isNull();
        assertThat(row.get("locale")).isEqualTo("fr-FR");
        assertThat(row.get("display_name")).isEqualTo("Alex");
        assertThat(mailbox.sentTo(email)).hasSize(1);
    }

    @Test
    void register_stores_the_client_locale_as_the_account_preference() {
        String email = freshEmail();

        assertThat(post(REGISTER, Map.of("email", email, "password", PASSWORD, "displayName", "Alex",
                "locale", "en-GB"))).hasStatus(HttpStatus.ACCEPTED);

        assertThat(jdbc.sql("SELECT locale FROM identity.user_account WHERE email = :e").param("e", email)
                .query(String.class).single()).isEqualTo("en-GB");
    }

    @Test
    void verification_token_is_stored_only_as_a_sha256_hash() {
        String email = freshEmail();
        register(email);
        String token = tokenSentTo(email);

        List<byte[]> hashes = jdbc.sql("""
                        SELECT t.token_hash FROM identity.one_time_token t
                        JOIN identity.user_account u ON u.id = t.user_id WHERE u.email = :e
                        """).param("e", email).query((rs, n) -> rs.getBytes(1)).list();

        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).hasSize(32).isNotEqualTo(token.getBytes(StandardCharsets.UTF_8));
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void registering_an_existing_email_is_indistinguishable_creates_no_account_and_sends_a_notice() {
        String email = freshEmail();
        MvcTestResult first = register(email);

        MvcTestResult second = register(email.toUpperCase());

        assertThat(second).hasStatus(HttpStatus.valueOf(first.getResponse().getStatus()));
        assertThat(text(second)).isEqualTo(text(first));
        assertThat(second.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(
                first.getResponse().getHeaderNames());
        assertThat(accountCount(email)).isEqualTo(1);
        List<EmailMessage> mails = mailbox.sentTo(email);
        assertThat(mails).hasSize(2);
        assertThat(mails.get(1).body()).doesNotContain(VERIFICATION_MARKER);
    }

    @Test
    void short_password_is_rejected_with_a_field_violation_and_nothing_is_created() {
        String email = freshEmail();

        MvcTestResult result = post(REGISTER, Map.of("email", email, "password", "too-short", "displayName", "A"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
            assertThat(body).extractingPath("$.errors[0].field").isEqualTo("password");
        });
        assertThat(text(result)).doesNotContain("too-short");
        assertThat(accountCount(email)).isZero();
    }

    @Test
    void invalid_fields_and_mass_assignment_are_rejected() {
        assertThat(post(REGISTER, Map.of("email", "not-an-email", "password", PASSWORD, "displayName", "A")))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(REGISTER, Map.of("email", freshEmail(), "password", PASSWORD, "displayName", " ")))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(REGISTER, Map.of("email", freshEmail(), "password", PASSWORD, "displayName", "x".repeat(61))))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(REGISTER, Map.of("email", freshEmail(), "password", "p".repeat(129), "displayName", "A")))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(REGISTER, Map.of("email", freshEmail(), "password", PASSWORD, "displayName", "A",
                "locale", "not a locale"))).hasStatus(HttpStatus.BAD_REQUEST);

        // Unknown (security-sensitive) properties are rejected outright: no client-supplied status or id.
        String email = freshEmail();
        assertThat(post(REGISTER, Map.of("email", email, "password", PASSWORD, "displayName", "A",
                "status", "ACTIVE", "id", UUID.randomUUID().toString()))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(accountCount(email)).isZero();
    }

    @Test
    void register_verify_login_and_create_a_household() {
        String email = freshEmail();
        register(email);
        assertThat(createHousehold(login(email))).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("EMAIL_NOT_VERIFIED");

        assertThat(post(VERIFY, Map.of("token", tokenSentTo(email)))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(statusOf(email)).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT email_verified_at IS NOT NULL FROM identity.user_account WHERE email = :e")
                .param("e", email).query(Boolean.class).single()).isTrue();
        assertThat(createHousehold(login(email))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void verification_token_is_single_use() {
        String email = freshEmail();
        register(email);
        String token = tokenSentTo(email);

        assertThat(post(VERIFY, Map.of("token", token))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(post(VERIFY, Map.of("token", token))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_OR_EXPIRED_TOKEN");
    }

    @Test
    void unknown_and_expired_tokens_give_the_same_generic_error_and_the_account_stays_pending() {
        String email = freshEmail();
        register(email);
        String token = tokenSentTo(email);
        jdbc.sql("""
                        UPDATE identity.one_time_token SET created_at = now() - interval '2 days',
                               expires_at = now() - interval '1 day'
                        WHERE user_id = (SELECT id FROM identity.user_account WHERE email = :e)
                        """).param("e", email).update();

        MvcTestResult expired = post(VERIFY, Map.of("token", token));
        MvcTestResult unknown = post(VERIFY, Map.of("token", "unknown-" + UUID.randomUUID()));

        assertThat(expired).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknown).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(jsonField(expired, "code")).isEqualTo("INVALID_OR_EXPIRED_TOKEN")
                .isEqualTo(jsonField(unknown, "code"));
        assertThat(jsonField(expired, "detail")).isEqualTo(jsonField(unknown, "detail"));
        assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void token_lifetime_is_24_hours() {
        String email = freshEmail();
        register(email);

        long hours = jdbc.sql("""
                        SELECT (extract(epoch FROM (t.expires_at - t.created_at)) / 3600)::bigint
                        FROM identity.one_time_token t
                        JOIN identity.user_account u ON u.id = t.user_id WHERE u.email = :e
                        """).param("e", email).query(Long.class).single();

        assertThat(hours).isEqualTo(24);
    }

    @Test
    void resend_sends_a_fresh_working_token_with_the_same_response_as_for_unknown_emails() {
        String email = freshEmail();
        register(email);

        MvcTestResult known = post(RESEND, Map.of("email", email));
        MvcTestResult unknown = post(RESEND, Map.of("email", freshEmail()));

        assertThat(known).hasStatus(HttpStatus.ACCEPTED);
        assertThat(unknown).hasStatus(HttpStatus.ACCEPTED);
        assertThat(text(known)).isEqualTo(text(unknown));
        assertThat(mailbox.sentTo(email)).hasSize(2);
        assertThat(post(VERIFY, Map.of("token", tokenSentTo(email)))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void resend_for_an_already_verified_account_sends_only_a_notice() {
        String email = freshEmail();
        register(email);
        post(VERIFY, Map.of("token", tokenSentTo(email)));

        assertThat(post(RESEND, Map.of("email", email))).hasStatus(HttpStatus.ACCEPTED);

        List<EmailMessage> mails = mailbox.sentTo(email);
        assertThat(mails).hasSize(2);
        assertThat(mails.get(1).body()).doesNotContain(VERIFICATION_MARKER);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM identity.one_time_token t
                        JOIN identity.user_account u ON u.id = t.user_id WHERE u.email = :e
                        """).param("e", email).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void database_rejects_a_token_with_a_bad_hash_length_inverted_expiry_or_unknown_purpose() {
        String email = freshEmail();
        register(email);
        UUID user = jdbc.sql("SELECT id FROM identity.user_account WHERE email = :e").param("e", email)
                .query(UUID.class).single();
        String insert = """
                INSERT INTO identity.one_time_token (id, user_id, purpose, token_hash, created_at, expires_at)
                VALUES (gen_random_uuid(), :u, :p, :h, now(), now() + :d * interval '1 hour')
                """;

        assertThatThrownBy(() -> jdbc.sql(insert).param("u", user).param("p", "EMAIL_VERIFY")
                .param("h", new byte[5]).param("d", 1).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).param("u", user).param("p", "EMAIL_VERIFY")
                .param("h", new byte[32]).param("d", -1).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).param("u", user).param("p", "OTHER")
                .param("h", new byte[32]).param("d", 1).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void register_and_resend_are_limited_per_address_with_429_problem_details() {
        String email = freshEmail();
        for (int i = 0; i < 5; i++) {
            assertThat(register(email)).hasStatus(HttpStatus.ACCEPTED);
        }

        MvcTestResult limited = register(email);

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
        String other = freshEmail();
        for (int i = 0; i < 5; i++) {
            assertThat(post(RESEND, Map.of("email", other))).hasStatus(HttpStatus.ACCEPTED);
        }
        assertThat(post(RESEND, Map.of("email", other))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void concurrent_registrations_of_the_same_email_create_exactly_one_account() throws Exception {
        String email = freshEmail();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> register(email).getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses).containsOnly(202);
        assertThat(accountCount(email)).isEqualTo(1);
        assertThat(mailbox.sentTo(email)).hasSize(5);
        assertThat(mailbox.sentTo(email).stream().filter(m -> m.body().contains(VERIFICATION_MARKER))).hasSize(1);
    }

    @Test
    void concurrent_verifications_with_one_token_consume_it_exactly_once() throws Exception {
        String email = freshEmail();
        register(email);
        String token = tokenSentTo(email);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> post(VERIFY, Map.of("token", token)).getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses.stream().filter(s -> s == 204)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 400)).hasSize(5);
        assertThat(statusOf(email)).isEqualTo("ACTIVE");
    }

    @Test
    void responses_never_contain_the_email_or_the_token_and_are_not_cacheable() {
        String email = freshEmail();
        MvcTestResult result = register(email);

        assertThat(text(result)).doesNotContain(email).doesNotContain(tokenSentTo(email));
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private String login(String email) {
        MvcTestResult result = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
        assertThat(result).hasStatusOk();
        return (String) json.readValue(text(result), Map.class).get("accessToken");
    }

    private MvcTestResult createHousehold(String accessToken) {
        return mvc.post().uri("/api/v1/households").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .content("{\"name\":\"Notre foyer\"}").exchange();
    }

    private String jsonField(MvcTestResult result, String field) {
        return (String) json.readValue(text(result), Map.class).get(field);
    }

    private static List<Integer> runConcurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Integer>> futures = pool.invokeAll(calls);
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
