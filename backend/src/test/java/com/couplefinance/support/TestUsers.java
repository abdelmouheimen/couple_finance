package com.couplefinance.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Seeds user accounts directly in the database (registration does not exist yet). Every call creates a new
 * user with a random id and email, so tests sharing the database never interfere.
 */
@TestComponent
public class TestUsers {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public TestUsers(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    /** An ACTIVE user able to log in with the given credentials (password hashed with the production encoder). */
    public UUID active(String email, String password) {
        return insert("ACTIVE", true, email, passwordEncoder.encode(password));
    }

    /** A PENDING_VERIFICATION user able to log in with the given credentials. */
    public UUID pendingVerification(String email, String password) {
        return insert("PENDING_VERIFICATION", false, email, passwordEncoder.encode(password));
    }

    /** An ACTIVE user whose stored password hash comes from another encoder family (hash given verbatim). */
    public UUID activeWithRawHash(String email, String rawPasswordHash) {
        return insert("ACTIVE", true, email, rawPasswordHash);
    }

    /** A deleted account tombstone: personal data erased, as after account deletion. */
    public UUID deleted() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO identity.user_account (id, locale, status, created_at, updated_at, deleted_at)
                        VALUES (:id, 'fr-FR', 'DELETED', :now, :now, :now)
                        """)
                .param("id", id)
                .param("now", now)
                .update();
        return id;
    }

    /** A user whose email is verified. */
    public UUID active() {
        return insert("ACTIVE", true);
    }

    /** A registered user who has not verified their email yet. */
    public UUID pendingVerification() {
        return insert("PENDING_VERIFICATION", false);
    }

    private UUID insert(String status, boolean verified) {
        return insert(status, verified, null, "not-a-real-hash");
    }

    private UUID insert(String status, boolean verified, String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO identity.user_account
                            (id, email, email_verified_at, password_hash, display_name, locale, status, created_at,
                             updated_at)
                        VALUES (:id, :email, :verifiedAt, :hash, 'Test user', 'fr-FR', :status, :now, :now)
                        """)
                .param("id", id)
                .param("email", email != null ? email : "user-" + id + "@example.test")
                .param("hash", passwordHash)
                .param("verifiedAt", verified ? now : null)
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }
}
