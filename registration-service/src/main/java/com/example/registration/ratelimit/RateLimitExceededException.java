package com.example.registration.ratelimit;

import java.time.Duration;

/**
 * RateLimitExceededException
 * Thrown when the caller is genuinely throttled: the action was refused because it
 * exceeded its bucket's rate, and {@link #getRetryAfter()} says how long to wait.
 *
 * <p>Distinct from a limiter being <em>unavailable</em> (Redis unreachable), which is
 * an infrastructure failure and not the caller's fault.
 */
public class RateLimitExceededException extends Exception {

    private final Duration retryAfter;

    public RateLimitExceededException(final Duration retryAfter) {
        super("Rate limit exceeded; retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    /**
     * @return how long the caller must wait before retrying, or {@code null} if unknown
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}