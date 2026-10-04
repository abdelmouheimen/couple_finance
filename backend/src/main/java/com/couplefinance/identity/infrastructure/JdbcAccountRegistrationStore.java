package com.couplefinance.identity.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.identity.domain.AccountRegistrationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRegistrationStore implements AccountRegistrationStore {

    private final JdbcClient jdbc;

    JdbcAccountRegistrationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertPendingAccount(UUID id, String normalizedEmail, String passwordHash, String displayName,
                                        String locale, Instant now) {
        // ON CONFLICT on uq_user_account_email: two concurrent registrations of one email create one account.
        return jdbc.sql("""
                        INSERT INTO identity.user_account
                            (id, email, password_hash, display_name, locale, status, created_at, updated_at)
                        VALUES (:id, :email, :hash, :displayName, :locale, 'PENDING_VERIFICATION', :now, :now)
                        ON CONFLICT (lower(email)) WHERE status <> 'DELETED' DO NOTHING
                        """)
                .param("id", id)
                .param("email", normalizedEmail)
                .param("hash", passwordHash)
                .param("displayName", displayName)
                .param("locale", locale)
                .param("now", utc(now))
                .update() == 1;
    }

    @Override
    public void insertEmailVerificationToken(UUID id, UUID userId, byte[] tokenHash, Instant createdAt,
                                             Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO identity.one_time_token (id, user_id, purpose, token_hash, created_at, expires_at)
                        VALUES (:id, :userId, 'EMAIL_VERIFY', :hash, :createdAt, :expiresAt)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    @Override
    public boolean consumeEmailVerificationToken(byte[] tokenHash, Instant now) {
        // One statement: the token is marked used and the account activated atomically; of two concurrent
        // consumers, only one finds used_at IS NULL.
        return jdbc.sql("""
                        WITH consumed AS (
                            UPDATE identity.one_time_token
                               SET used_at = :now
                             WHERE token_hash = :hash AND purpose = 'EMAIL_VERIFY'
                               AND used_at IS NULL AND expires_at > :now
                         RETURNING user_id)
                        UPDATE identity.user_account u
                           SET status = 'ACTIVE', email_verified_at = :now, updated_at = :now, version = u.version + 1
                          FROM consumed
                         WHERE u.id = consumed.user_id AND u.status = 'PENDING_VERIFICATION'
                        """)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
