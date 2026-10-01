package com.couplefinance.shared.error;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** Error codes that are not specific to a business module. */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    CommonErrorCode(HttpStatus status) {
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

    /**
     * Code used for framework-raised errors, which only carry a status. For 400 the more specific
     * {@link #VALIDATION_FAILED} is assigned explicitly by the handler where it applies.
     */
    static CommonErrorCode forStatus(HttpStatusCode status) {
        Optional<CommonErrorCode> match = Arrays.stream(values())
                .filter(candidate -> candidate != VALIDATION_FAILED)
                .filter(candidate -> candidate.status.value() == status.value())
                .findFirst();
        return match.orElse(status.is5xxServerError() ? INTERNAL_ERROR : MALFORMED_REQUEST);
    }
}
