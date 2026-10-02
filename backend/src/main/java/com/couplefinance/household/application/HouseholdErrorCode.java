package com.couplefinance.household.application;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum HouseholdErrorCode implements ErrorCode {

    /** BR-HH-02: a user belongs to at most one active household. */
    ALREADY_IN_HOUSEHOLD(HttpStatus.CONFLICT),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST),
    INVALID_TIMEZONE(HttpStatus.BAD_REQUEST),
    /** BR-HH-01 / BR-HH-04 / BR-HH-10: only an ACTIVE household with exactly one active member can invite. */
    INVITATION_NOT_ALLOWED(HttpStatus.CONFLICT),
    /** Unknown invitation, another household's, or one created by the other member: indistinguishable. */
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** The invitation was already redeemed: nothing left to revoke (expired ones stay revocable, harmlessly). */
    INVITATION_NOT_REVOCABLE(HttpStatus.CONFLICT);

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
