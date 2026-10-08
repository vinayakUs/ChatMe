package com.example.registration.ratelimit;

import java.time.Instant;
import java.util.Optional;

/**
 * RateLimiter interface for comman rate limiter.
 *
 * @param <K> type of key that identify a rate limit key
 */
public interface RateLimiter<K> {

    /**
     * Returns the next time at which this rate limiter will permit the rate-limited action to be taken
     *
     * @param key key that identifies the action
     * @return Return next time when ratelimiter will permit rate limited action to be taken.
     * if return time is before or equals current time caller may take actions immediately
     * if empty callers may need to take some action
     */
    Optional<Instant> getNextActionTime(K key);

    /**
     * Checks whether the action identified by the given key may be taken
     *
     * @param key that identifies the action
     *
     * @throws RateLimitExceededException if caller must wait before taking the action
     */
    void checkRateLimit(K key) throws RateLimitExceededException;
}
