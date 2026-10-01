package com.couplefinance.shared.money;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes raised by {@link Money} when a client-facing, expected rule is violated. */
public enum MoneyErrorCode implements ErrorCode {

    /** BR-MON-04: an operation combined or compared amounts of different currencies. */
    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST),
    /** BR-MON-03: the decimal string has more fractional digits than the currency allows. */
    TOO_MANY_DECIMALS(HttpStatus.BAD_REQUEST),
    /** BR-MON-02 / BR-MON-03: the decimal string is not a plain, non-exponential, non-localized decimal. */
    INVALID_AMOUNT_FORMAT(HttpStatus.BAD_REQUEST),
    /** BR-MON-01: the currency property is not a 3-letter ISO 4217 code. */
    INVALID_CURRENCY_FORMAT(HttpStatus.BAD_REQUEST),
    /** BR-MON-07: the amount is not strictly positive. */
    AMOUNT_NOT_POSITIVE(HttpStatus.BAD_REQUEST),
    /** BR-MON-07: the amount exceeds the per-currency maximum. */
    AMOUNT_EXCEEDS_MAXIMUM(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    MoneyErrorCode(HttpStatus status) {
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
