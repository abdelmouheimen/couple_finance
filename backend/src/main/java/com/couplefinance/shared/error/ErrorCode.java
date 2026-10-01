package com.couplefinance.shared.error;

import org.springframework.http.HttpStatus;

/**
 * A stable, machine-readable error identifier returned as the {@code code} property of every Problem Details
 * response (RFC 9457). Modules define their own codes (e.g. {@code EXPENSE_VERSION_CONFLICT}) by implementing
 * this interface, typically with an enum.
 */
public interface ErrorCode {

    /** Stable identifier, UPPER_SNAKE_CASE. Never change a published code. */
    String code();

    /** HTTP status sent with this error. */
    HttpStatus status();
}
