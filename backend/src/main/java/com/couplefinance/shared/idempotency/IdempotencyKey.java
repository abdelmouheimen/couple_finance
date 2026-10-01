package com.couplefinance.shared.idempotency;

import java.util.Optional;

import com.couplefinance.shared.error.ApplicationException;
import org.jspecify.annotations.Nullable;

/**
 * A client-generated {@code Idempotency-Key} (BR-EXP-13): 1 to 100 visible ASCII characters. The value is
 * client-controlled and only ever used as a bind parameter; it is never logged.
 */
public record IdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    public static final int MAX_LENGTH = 100;

    public IdempotencyKey {
        if (!isValid(value)) {
            throw new ApplicationException(IdempotencyErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "The Idempotency-Key header must be 1 to " + MAX_LENGTH + " printable ASCII characters.");
        }
    }

    /**
     * Parses the header value of an endpoint that opts in to idempotency.
     *
     * @return empty when the header is absent (the endpoint decides whether a key is mandatory)
     * @throws ApplicationException {@code IDEMPOTENCY_KEY_INVALID} (400) when present but malformed
     */
    public static Optional<IdempotencyKey> fromHeader(@Nullable String header) {
        return header == null ? Optional.empty() : Optional.of(new IdempotencyKey(header));
    }

    private static boolean isValid(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        return value.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
    }

    /** Never print the key. */
    @Override
    public String toString() {
        return "IdempotencyKey[***]";
    }
}
