package com.couplefinance.household.api;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Errors raised through the household facade, shared by every module that guards writes. */
public enum HouseholdAccessErrorCode implements ErrorCode {

    /** BR-HH-10: a dissolved household accepts reads and exports only. */
    HOUSEHOLD_READ_ONLY(HttpStatus.FORBIDDEN),
    /** The caller has no household and no archive access; indistinguishable from "does not exist". */
    HOUSEHOLD_NOT_FOUND(HttpStatus.NOT_FOUND);

    private final HttpStatus status;

    HouseholdAccessErrorCode(HttpStatus status) {
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
