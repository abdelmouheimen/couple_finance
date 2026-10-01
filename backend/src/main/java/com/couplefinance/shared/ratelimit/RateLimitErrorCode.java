package com.couplefinance.shared.ratelimit;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum RateLimitErrorCode implements ErrorCode {

    RATE_LIMITED;

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }
}
