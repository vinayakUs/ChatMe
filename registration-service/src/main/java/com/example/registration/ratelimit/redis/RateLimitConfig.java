package com.example.registration.ratelimit.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    public static LeakyBucketConfigRegistrar leakyBucketConfigRegistrar() {
        return new LeakyBucketConfigRegistrar();
    }
}