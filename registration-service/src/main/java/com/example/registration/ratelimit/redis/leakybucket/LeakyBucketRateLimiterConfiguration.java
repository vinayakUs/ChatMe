package com.example.registration.ratelimit.redis.leakybucket;

import java.time.Duration;

/**
 * RedisLeakyBucketRateLimiterConfiguration
 */
public class LeakyBucketRateLimiterConfiguration {

    String name;
    int maxCapacity;
    Duration permitRegenerationPeriod;
    Duration minDelay;

    public int getMaxCapacity() {
        return maxCapacity;
    }
    public Duration getMinDelay() {
        return minDelay;
    }
    public String getName() {
        return name;
    }
    public Duration getPermitRegenerationPeriod() {
        return permitRegenerationPeriod;
    }
    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }
    public void setMinDelay(Duration minDelay) {
        this.minDelay = minDelay;
    }
    public void setName(String name) {
        this.name = name;
    }
    public void setPermitRegenerationPeriod(Duration permitRegenerationPeriod) {
        this.permitRegenerationPeriod = permitRegenerationPeriod;
    }
}
