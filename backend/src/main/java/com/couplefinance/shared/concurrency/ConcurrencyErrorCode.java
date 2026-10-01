package com.couplefinance.shared.concurrency;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the optimistic-concurrency contract (BR-EXP-12, architecture.md §7). */
public enum ConcurrencyErrorCode implements ErrorCode {

    /** {@code If-Match} absent on an update endpoint. */
    IF_MATCH_REQUIRED(HttpStatus.PRECONDITION_REQUIRED),
    /** {@code If-Match} present but not a version token. */
    IF_MATCH_INVALID(HttpStatus.BAD_REQUEST),
    /** The version sent no longer matches the stored one; the write was not applied. */
    VERSION_CONFLICT(HttpStatus.PRECONDITION_FAILED);

    private final HttpStatus status;

    ConcurrencyErrorCode(HttpStatus status) {
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
