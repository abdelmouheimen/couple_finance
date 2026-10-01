package com.couplefinance.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Idempotency-Key end to end on a test creating endpoint (BR-EXP-13, Issue #4). */
@IntegrationTest
class IdempotencyIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IdempotencyService service;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void BR_EXP_13_same_key_and_same_request_replays_the_original_response_without_executing_again() throws Exception {
        UUID user = users.active();
        String key = key();

        MvcTestResult first = post(user, key, "lunch", 0);
        MvcTestResult second = post(user, key, "lunch", 0);

        assertThat(first).hasStatus(HttpStatus.CREATED).headers().hasValue("Idempotent-Replayed", "false");
        assertThat(second).hasStatus(HttpStatus.CREATED).headers().hasValue("Idempotent-Replayed", "true");
        assertThat(second.getResponse().getContentAsString()).isEqualToIgnoringWhitespace(first.getResponse().getContentAsString());
        assertThat(executions(user)).isEqualTo(1);
    }

    @Test
    void BR_EXP_13_same_key_with_a_different_request_is_422_and_nothing_is_executed() {
        UUID user = users.active();
        String key = key();
        post(user, key, "lunch", 0);

        assertThat(post(user, key, "dinner", 0)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(executions(user)).isEqualTo(1);
    }

    @Test
    void BR_EXP_13_same_key_while_the_first_request_is_in_progress_is_409() throws Exception {
        UUID user = users.active();
        String key = key();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcTestResult> slow = pool.submit(() -> post(user, key, "slow", 1500));
            waitUntilClaimed(user, key);

            assertThat(post(user, key, "slow", 1500)).hasStatus(HttpStatus.CONFLICT)
                    .bodyJson().extractingPath("$.code").isEqualTo("REQUEST_IN_PROGRESS");
            // a different request under the same key is a mismatch, not "in progress"
            assertThat(post(user, key, "other", 0)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

            assertThat(slow.get()).hasStatus(HttpStatus.CREATED);
            assertThat(post(user, key, "slow", 1500)).hasStatus(HttpStatus.CREATED)
                    .headers().hasValue("Idempotent-Replayed", "true");
            assertThat(executions(user)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void BR_EXP_13_two_simultaneous_requests_with_the_same_key_execute_exactly_once() throws Exception {
        UUID user = users.active();
        String key = key();
        int contenders = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return post(user, key, "race", 300).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(status -> status == 201).isNotEmpty();
            assertThat(statuses).allMatch(status -> status == 201 || status == 409);
            assertThat(executions(user)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void BR_EXP_13_user_b_reusing_the_exact_key_and_request_of_user_a_executes_on_its_own() throws Exception {
        UUID a = users.active();
        UUID b = users.active();
        String key = key();
        MvcTestResult resultA = post(a, key, "same", 0);

        MvcTestResult resultB = post(b, key, "same", 0);

        assertThat(resultB).headers().hasValue("Idempotent-Replayed", "false");
        assertThat(resultB.getResponse().getContentAsString()).isNotEqualTo(resultA.getResponse().getContentAsString());
        assertThat(executions(a)).isEqualTo(1);
        assertThat(executions(b)).isEqualTo(1);
    }

    @Test
    void BR_EXP_13_an_expired_key_can_be_reused_and_executes_again() throws Exception {
        UUID user = users.active();
        String key = key();
        post(user, key, "lunch", 0);
        expire(user, key);

        // expiry also frees the key for a different request
        assertThat(post(user, key, "dinner", 0)).hasStatus(HttpStatus.CREATED)
                .headers().hasValue("Idempotent-Replayed", "false");
        assertThat(executions(user)).isEqualTo(2);
    }

    @Test
    void BR_EXP_13_keys_are_valid_for_24_hours() {
        UUID user = users.active();
        String key = key();
        post(user, key, "lunch", 0);

        long hours = jdbc.sql("""
                        SELECT EXTRACT(EPOCH FROM (expires_at - created_at)) / 3600 FROM infra.idempotency_key
                        WHERE user_id = :user AND key = :key
                        """).param("user", user).param("key", key).query(Long.class).single();
        assertThat(hours).isEqualTo(24);
    }

    @Test
    void BR_EXP_13_purge_removes_expired_keys_only() {
        UUID user = users.active();
        String expired = key();
        String fresh = key();
        post(user, expired, "a", 0);
        post(user, fresh, "b", 0);
        expire(user, expired);

        assertThat(service.purgeExpired()).isGreaterThanOrEqualTo(1);

        assertThat(keyCount(user, expired)).isZero();
        assertThat(keyCount(user, fresh)).isEqualTo(1);
    }

    @Test
    void a_failed_request_releases_the_key_so_the_client_can_retry() {
        UUID user = users.active();
        String key = key();

        assertThat(post(user, key, "fail", 0)).hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(keyCount(user, key)).isZero();
        // the same key is usable again, for any request
        assertThat(post(user, key, "fail", 0)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void request_without_a_key_is_not_idempotent_and_still_works() {
        UUID user = users.active();

        assertThat(post(user, null, "lunch", 0)).hasStatus(HttpStatus.CREATED);
        assertThat(post(user, null, "lunch", 0)).hasStatus(HttpStatus.CREATED);
        assertThat(executions(user)).isEqualTo(2);
    }

    @Test
    void malformed_key_is_400_and_nothing_is_executed() {
        UUID user = users.active();

        assertThat(post(user, "a".repeat(101), "lunch", 0)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(post(user, "", "lunch", 0)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(executions(user)).isZero();
    }

    @Test
    void unauthenticated_request_is_rejected_and_stores_no_key() {
        String key = key();

        assertThat(mvc.post().uri("/test-support/idempotent").header(IdempotencyKey.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"delayMs\":0}").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.sql("SELECT count(*) FROM infra.idempotency_key WHERE key = :key").param("key", key)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void execute_refuses_to_run_inside_a_caller_transaction() {
        UUID user = users.active();
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> service.execute(user,
                new IdempotencyKey(key()), "op", RequestHash.forOperation("op").build(),
                () -> new IdempotentResponse(201, null)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void database_rejects_an_inconsistent_status_and_response() {
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO infra.idempotency_key
                            (user_id, key, operation, request_hash, status, created_at, expires_at)
                        VALUES (:user, 'k', 'op', decode(repeat('00', 32), 'hex'), 'DONE', now(), now() + interval '1 day')
                        """).param("user", user).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void waitUntilClaimed(UUID user, String key) throws InterruptedException {
        for (int i = 0; i < 100 && keyCount(user, key) == 0; i++) {
            Thread.sleep(20);
        }
        assertThat(keyCount(user, key)).isEqualTo(1);
    }

    private void expire(UUID user, String key) {
        jdbc.sql("""
                        UPDATE infra.idempotency_key
                        SET created_at = now() - interval '25 hours', expires_at = now() - interval '1 hour'
                        WHERE user_id = :user AND key = :key
                        """).param("user", user).param("key", key).update();
    }

    private int keyCount(UUID user, String key) {
        return jdbc.sql("SELECT count(*) FROM infra.idempotency_key WHERE user_id = :user AND key = :key")
                .param("user", user).param("key", key).query(Integer.class).single();
    }

    private int executions(UUID user) {
        return jdbc.sql("SELECT count(*) FROM test_support.idempotent_probe WHERE owner_user_id = :user")
                .param("user", user).query(Integer.class).single();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private MvcTestResult post(UUID user, String key, String name, long delayMs) {
        var request = mvc.post().uri("/test-support/idempotent")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"delayMs\":" + delayMs + "}");
        if (key != null) {
            request.header(IdempotencyKey.HEADER, key);
        }
        return request.exchange();
    }
}
