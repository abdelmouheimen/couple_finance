package com.couplefinance.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seeds user accounts directly in the database (registration does not exist yet). Every call creates a new
 * user with a random id and email, so tests sharing the database never interfere.
 */
@TestComponent
public class TestUsers {

    private final JdbcClient jdbc;

    public TestUsers(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO identity.user_account
                            (id, email, email_verified_at, password_hash, display_name, locale, status, created_at,
                             updated_at)
                        VALUES (:id, :email, :verifiedAt, 'not-a-real-hash', 'Test user', 'fr-FR', :status, :now, :now)
                        """)
                .param("id", id)
                .param("email", "user-" + id + "@example.test")
                .param("verifiedAt", verified ? now : null)
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }
}
