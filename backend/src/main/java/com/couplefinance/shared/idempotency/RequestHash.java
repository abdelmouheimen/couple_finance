package com.couplefinance.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * SHA-256 of the canonical form of a request (BR-EXP-13): two requests are "the same" exactly when their
 * operation and named fields are equal. Canonicalisation is independent of field order and of whitespace or
 * key order in the original JSON, because callers pass the already-parsed fields, not the raw body. Every part
 * is length-prefixed so that no two different requests share an encoding, and an absent value ({@code null})
 * differs from an empty string.
 *
 * <p>Callers include every field that changes the outcome (and the target resource ids). The user is the
 * key's scope, so it is not part of the hash.
 */
public final class RequestHash {

    private RequestHash() {
    }

    public static Builder forOperation(String operation) {
        return new Builder(operation);
    }

    public static final class Builder {

        private final String operation;
        private final Map<String, @Nullable String> fields = new TreeMap<>();

        private Builder(String operation) {
            this.operation = operation;
        }

        /** Adds (or replaces) a field; the value must be the canonical text of the field. */
        public Builder field(String name, @Nullable String value) {
            fields.put(name, value);
            return this;
        }

        /** The 32-byte SHA-256 digest. */
        public byte[] build() {
            StringBuilder canonical = new StringBuilder();
            append(canonical, operation);
            fields.forEach((name, value) -> {
                append(canonical, name);
                if (value == null) {
                    canonical.append('-');
                } else {
                    canonical.append('+');
                    append(canonical, value);
                }
            });
            return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
        }

        private static void append(StringBuilder out, String part) {
            out.append(part.getBytes(StandardCharsets.UTF_8).length).append(':').append(part).append(';');
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    /** Constant-time comparison. */
    static boolean matches(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
