package com.example.registration.ratelimit.redis;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.example.registration.ratelimit.RedisLeakyBucketRateLimiterConfiguration;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

@Component("session-creation")
public class RedisLeakyBucketSessionCreationRateLimiter
        extends RedisLeakyBucketRateLimiter<PhoneNumberAndCollationKey> {

    public RedisLeakyBucketSessionCreationRateLimiter(StringRedisTemplate redisTemplate, Clock clock,
            @Qualifier("session-creation") RedisLeakyBucketRateLimiterConfiguration configuration) {
        super(redisTemplate, clock, configuration);
    }

    @Override
    String getBucketName(PhoneNumberAndCollationKey key) {

        return "Session-Creation::{}:{}".formatted(
                PhoneNumberUtil.getInstance().format(key.phoneNumber(), PhoneNumberUtil.PhoneNumberFormat.E164),
                key.collationKey());
    }

    @Override
    boolean shouldFailOpen() {
        return true;
    }

}
