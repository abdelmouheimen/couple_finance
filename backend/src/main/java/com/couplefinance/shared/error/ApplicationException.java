package com.couplefinance.shared.error;

/**
 * Base class for expected, client-facing errors raised by use cases and domain objects. Rendered as Problem
 * Details with the error code's status and code.
 *
 * <p>The message is sent to the client as {@code detail}: it must never contain internal details, SQL, or data
 * belonging to another user or household (security.md §5).
 */
public class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApplicationException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ApplicationException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
