package com.couplefinance.shared.idempotency;

import org.jspecify.annotations.Nullable;

/** A stored key as read back from {@code infra.idempotency_key}. */
record IdempotencyEntry(String operation, byte[] requestHash, boolean done, @Nullable Integer responseStatus,
        @Nullable String responseBody) {
}
