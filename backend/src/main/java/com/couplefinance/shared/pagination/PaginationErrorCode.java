package com.couplefinance.shared.pagination;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Errors of the common cursor-pagination convention. */
public enum PaginationErrorCode implements ErrorCode {

    /** Cursor malformed, tampered with, produced with another key, or issued for another caller / listing. */
    INVALID_CURSOR,
    /** {@code limit} is lower than 1. */
    INVALID_LIMIT;

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
