package com.couplefinance.shared.ratelimit;

import com.couplefinance.shared.error.ApplicationException;

/** A rate limit was exceeded; rendered as 429 Problem Details with a {@code Retry-After} header. */
public class RateLimitExceededException extends ApplicationException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(RateLimitErrorCode.RATE_LIMITED, "Too many requests. Retry later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
