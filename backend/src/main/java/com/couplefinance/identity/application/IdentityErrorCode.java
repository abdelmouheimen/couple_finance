package com.couplefinance.identity.application;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

enum IdentityErrorCode implements ErrorCode {

    /** The account exists but its email is not verified yet (domain-model.md §3). */
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),

    /** Wrong password, unknown email or deleted account: one generic code, no account enumeration. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED);

    private final HttpStatus status;

    IdentityErrorCode(HttpStatus status) {
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
