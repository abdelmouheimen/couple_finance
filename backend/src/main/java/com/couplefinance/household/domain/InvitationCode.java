package com.couplefinance.household.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * The secret invitation code (BR-HH-04): {@value #RANDOM_BYTES} bytes (256 bits, above the required 128) from a
 * secure random source, URL-safe Base64. Only its SHA-256 hash is persisted; the code itself is shown once and
 * never logged ({@link #toString()} is redacted). A fast hash is sufficient because the code is high-entropy
 * random, not a user-chosen secret.
 */
public final class InvitationCode {

    public static final int RANDOM_BYTES = 32;

    private final String value;

    private InvitationCode(String value) {
        this.value = value;
    }

    public static InvitationCode generate(SecureRandom random) {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return new InvitationCode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** Wraps a code presented by a user (for later redemption lookups). */
    public static InvitationCode of(String value) {
        return new InvitationCode(Objects.requireNonNull(value, "value"));
    }

    /** The plaintext code. Return it to the creator once; never store or log it. */
    public String value() {
        return value;
    }

    /** SHA-256 of the code: 32 bytes, the {@code code_hash} column. */
    public byte[] hash() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    @Override
    public String toString() {
        return "InvitationCode[redacted]";
    }
}
