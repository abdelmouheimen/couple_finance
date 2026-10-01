package com.couplefinance.shared.idempotency;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of {@code infra.idempotency_key} (database-schema.md §12.1). Every statement but the purge is
 * scoped by {@code user_id}. Not transactional itself: {@link IdempotencyService} runs each call in its own
 * short transaction so that a claim is visible to concurrent requests immediately.
 */
@Repository
class JdbcIdempotencyStore {

    private final JdbcClient jdbc;

    JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key with an insert; the primary key arbitrates concurrent claims. A row that expired is taken
     * over atomically by the same statement.
     *
     * @return true when this caller now owns the key and must execute the request
     */
    boolean claim(UUID userId, String key, String operation, byte[] requestHash, Instant now, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO infra.idempotency_key
                            (user_id, key, operation, request_hash, status, created_at, expires_at)
                        VALUES (:userId, :key, :operation, :hash, 'IN_PROGRESS', :now, :expiresAt)
                        ON CONFLICT (user_id, key) DO UPDATE SET
                            operation = EXCLUDED.operation, request_hash = EXCLUDED.request_hash,
                            status = 'IN_PROGRESS', response_status = NULL, response_body = NULL,
                            created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                        WHERE infra.idempotency_key.expires_at <= EXCLUDED.created_at
                        """)
                .param("userId", userId).param("key", key).param("operation", operation)
                .param("hash", requestHash).param("now", utc(now)).param("expiresAt", utc(expiresAt))
                .update() == 1;
    }

    Optional<IdempotencyEntry> find(UUID userId, String key) {
        return jdbc.sql("""
                        SELECT operation, request_hash, status, response_status, response_body::text AS body
                        FROM infra.idempotency_key WHERE user_id = :userId AND key = :key
                        """)
                .param("userId", userId).param("key", key)
                .query((rs, row) -> new IdempotencyEntry(rs.getString("operation"), rs.getBytes("request_hash"),
                        "DONE".equals(rs.getString("status")), (Integer) rs.getObject("response_status"),
                        rs.getString("body")))
                .optional();
    }

    /** Records the response, only while our own claim (same hash, same claim instant) is still in progress. */
    boolean complete(UUID userId, String key, byte[] requestHash, Instant claimedAt, IdempotentResponse response) {
        return jdbc.sql("""
                        UPDATE infra.idempotency_key
                        SET status = 'DONE', response_status = :status, response_body = CAST(:body AS jsonb)
                        WHERE user_id = :userId AND key = :key AND status = 'IN_PROGRESS'
                          AND request_hash = :hash AND created_at = :claimedAt
                        """)
                .param("claimedAt", utc(claimedAt)).param("status", (short) response.status()).param("body", response.jsonBody())
                .param("userId", userId).param("key", key).param("hash", requestHash)
                .update() == 1;
    }

    /** Frees a claim whose request failed, so the client can retry. */
    void release(UUID userId, String key, byte[] requestHash, Instant claimedAt) {
        jdbc.sql("""
                        DELETE FROM infra.idempotency_key
                        WHERE user_id = :userId AND key = :key AND status = 'IN_PROGRESS' AND request_hash = :hash
                          AND created_at = :claimedAt
                        """)
                .param("userId", userId).param("key", key).param("hash", requestHash)
                .param("claimedAt", utc(claimedAt)).update();
    }

    /** Dedicated purge: deliberately not scoped by user (database.md retention of idempotency keys). */
    int purgeExpired(Instant now) {
        return jdbc.sql("DELETE FROM infra.idempotency_key WHERE expires_at <= :now")
                .param("now", utc(now)).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
