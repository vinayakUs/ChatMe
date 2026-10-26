package com.example.registration.ratelimit.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.registration.ratelimit.redis.leakybucket.LeakyBucketRateLimiterConfiguration;

@Configuration
public class RateLimitConfig {

    @Bean("session-creation-config")
    @ConfigurationProperties("rate-limits.leaky-bucket.session-creation-config")
    public LeakyBucketRateLimiterConfiguration sessionCreationConfig() {
        return new LeakyBucketRateLimiterConfiguration();
    }

}