package com.couplefinance.shared.idempotency;

/** Outcome of {@link IdempotencyService#execute}: the response and whether it is a replay of an earlier one. */
public record IdempotentResult(IdempotentResponse response, boolean replayed) {
}
