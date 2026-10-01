package com.couplefinance.household.application;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum HouseholdErrorCode implements ErrorCode {

    /** BR-HH-02: a user belongs to at most one active household. */
    ALREADY_IN_HOUSEHOLD(HttpStatus.CONFLICT),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST),
    INVALID_TIMEZONE(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    HouseholdErrorCode(HttpStatus status) {
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
