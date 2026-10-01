package com.couplefinance.shared.idempotency;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the {@code Idempotency-Key} contract (BR-EXP-13). */
public enum IdempotencyErrorCode implements ErrorCode {

    /** The header is present but not a valid key (empty, longer than 100 characters, not printable ASCII). */
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST),
    /** The first request with this key is still being processed. */
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
    /** The key was already used for a different request. */
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT);

    private final HttpStatus status;

    IdempotencyErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
