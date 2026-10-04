package com.couplefinance.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Atomic persistence operations of registration and email verification. Every method is a single conditional SQL
 * statement so that concurrent calls are resolved by PostgreSQL (unique index, conditional update), not by checks.
 */
public interface AccountRegistrationStore {

    /**
     * Inserts a {@code PENDING_VERIFICATION} account unless a non-deleted account already uses the email
     * (case-insensitive).
     *
     * @return {@code true} when the account was created
     */
    boolean insertPendingAccount(UUID id, String normalizedEmail, String passwordHash, String displayName,
                                 String locale, Instant now);

    void insertEmailVerificationToken(UUID id, UUID userId, byte[] tokenHash, Instant createdAt, Instant expiresAt);

    /**
     * Consumes an unused, unexpired EMAIL_VERIFY token and activates its pending account in one atomic statement.
     *
     * @return {@code true} when this call consumed the token and activated the account
     */
    boolean consumeEmailVerificationToken(byte[] tokenHash, Instant now);
}
