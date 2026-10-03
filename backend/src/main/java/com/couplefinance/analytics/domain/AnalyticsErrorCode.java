package com.couplefinance.analytics.domain;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable error codes of the analytics module. */
public enum AnalyticsErrorCode implements ErrorCode {

    /** BR-HH-07: the path date is not the start of an existing period of the household calendar (404). */
    ANALYTICS_PERIOD_NOT_FOUND(HttpStatus.NOT_FOUND);

    private final HttpStatus status;

    AnalyticsErrorCode(HttpStatus status) {
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
