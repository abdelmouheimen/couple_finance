package com.couplefinance.categorization.domain;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CategoryErrorCode implements ErrorCode {

    /** BR-CAT-02: a custom category name is unique per household, case-insensitively. */
    CATEGORY_NAME_ALREADY_EXISTS(HttpStatus.CONFLICT),
    /** BR-CAT-03: a system category cannot be renamed, archived or hidden by a household. */
    SYSTEM_CATEGORY_IMMUTABLE(HttpStatus.FORBIDDEN),
    /** The household reached the technical maximum of custom categories (sort_order is a smallint). */
    CATEGORY_LIMIT_REACHED(HttpStatus.CONFLICT);

    private final HttpStatus status;

    CategoryErrorCode(HttpStatus status) {
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
