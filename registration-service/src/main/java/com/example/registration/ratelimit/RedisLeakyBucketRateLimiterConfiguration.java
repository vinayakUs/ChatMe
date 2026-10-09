package com.example.registration.ratelimit;

import java.time.Duration;

/**
 * RedisLeakyBucketRateLimiterConfiguration
 */
public record RedisLeakyBucketRateLimiterConfiguration(
        String name, int maxCapacity, Duration permitRegenerationPeriod, Duration minDelay) {}
