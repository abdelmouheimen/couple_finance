package com.couplefinance.shared.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** ETag / If-Match end to end on a test aggregate (BR-EXP-12, Issue #5). */
@IntegrationTest
class VersionedProbeIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Test
    void BR_EXP_12_read_returns_the_version_as_etag() {
        UUID user = users.active();
        UUID id = probe(user);

        assertThat(get(user, id)).hasStatus(HttpStatus.OK).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
    }

    @Test
    void BR_EXP_12_matching_if_match_updates_and_increments_the_version() {
        UUID user = users.active();
        UUID id = probe(user);

        assertThat(put(user, id, "\"0\"", "renamed"))
                .hasStatus(HttpStatus.OK).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(nameOf(id)).isEqualTo("renamed");
        assertThat(versionOf(id)).isEqualTo(1);
    }

    @Test
    void BR_EXP_12_missing_if_match_is_428_and_nothing_is_written() {
        UUID user = users.active();
        UUID id = probe(user);

        assertThat(put(user, id, null, "renamed")).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("IF_MATCH_REQUIRED");
        assertThat(nameOf(id)).isEqualTo("original");
        assertThat(versionOf(id)).isZero();
    }

    @Test
    void malformed_if_match_is_400_problem_details() {
        UUID user = users.active();
        UUID id = probe(user);

        assertThat(put(user, id, "W/\"0\"", "renamed")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("IF_MATCH_INVALID");
        assertThat(put(user, id, "*", "renamed")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(nameOf(id)).isEqualTo("original");
    }

    @Test
    void BR_EXP_12_stale_if_match_is_412_and_the_stale_write_is_not_applied() {
        UUID user = users.active();
        UUID id = probe(user);
        assertThat(put(user, id, "\"0\"", "first")).hasStatus(HttpStatus.OK);

        MvcTestResult stale = put(user, id, "\"0\"", "stale");

        assertThat(stale).hasStatus(HttpStatus.PRECONDITION_FAILED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("VERSION_CONFLICT");
                    assertThat(json).extractingPath("$.detail").asString().doesNotContain("SQL", "Exception");
                });
        assertThat(nameOf(id)).isEqualTo("first");
        assertThat(versionOf(id)).isEqualTo(1);
    }

    @Test
    void BR_EXP_12_persistence_level_optimistic_lock_failure_is_translated_to_412() {
        UUID user = users.active();
        UUID id = probe(user);

        MvcTestResult result = mvc.put().uri("/test-support/versioned/{id}/lost-update", id)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"lost\"}").exchange();

        assertThat(result).hasStatus(HttpStatus.PRECONDITION_FAILED)
                .bodyJson().extractingPath("$.code").isEqualTo("VERSION_CONFLICT");
        assertThat(nameOf(id)).isEqualTo("original");
    }

    @Test
    void another_users_resource_is_404_before_any_precondition_is_evaluated() {
        UUID owner = users.active();
        UUID id = probe(owner);
        UUID intruder = users.active();

        assertThat(put(intruder, id, null, "x")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(put(intruder, id, "garbage", "x")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(put(intruder, id, "\"99\"", "x")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(nameOf(id)).isEqualTo("original");
    }

    @Test
    void unauthenticated_update_is_rejected() {
        assertThat(mvc.put().uri("/test-support/versioned/{id}", UUID.randomUUID())
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_EXP_12_concurrent_updates_with_the_same_version_exactly_one_succeeds() throws Exception {
        UUID user = users.active();
        UUID id = probe(user);
        int contenders = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                String name = "writer-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return put(user, id, "\"0\"", name).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 412).hasSize(contenders - 1);
            assertThat(versionOf(id)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID probe(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO test_support.versioned_probe (id, owner_user_id, name) VALUES (:id, :owner, 'original')")
                .param("id", id).param("owner", owner).update();
        return id;
    }

    private MvcTestResult get(UUID user, UUID id) {
        return mvc.get().uri("/test-support/versioned/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private MvcTestResult put(UUID user, UUID id, String ifMatch, String name) {
        var request = mvc.put().uri("/test-support/versioned/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}");
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private String nameOf(UUID id) {
        return jdbc.sql("SELECT name FROM test_support.versioned_probe WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private long versionOf(UUID id) {
        return jdbc.sql("SELECT version FROM test_support.versioned_probe WHERE id = :id").param("id", id)
                .query(Long.class).single();
    }
}
