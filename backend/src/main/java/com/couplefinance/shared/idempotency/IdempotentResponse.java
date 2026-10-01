package com.couplefinance.shared.idempotency;

import org.jspecify.annotations.Nullable;

/**
 * The response to replay: HTTP status and JSON body text (absent for body-less responses). The body may hold
 * PERSONAL data; it is stored for at most 24 h and replayed only to the user who made the original request.
 */
public record IdempotentResponse(int status, @Nullable String jsonBody) {

    public IdempotentResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("not an HTTP status: " + status);
        }
    }
}
