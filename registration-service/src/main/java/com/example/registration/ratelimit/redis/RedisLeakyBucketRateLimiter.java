package com.example.registration.ratelimit.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.RedisLeakyBucketRateLimiterConfiguration;

/**
 * RedisLeakyBucketRateLimiter
 * Hybrid leaky/token-bucket algorithm — token-bucket-style lazy permit
 * refill capped at maxCapacity, combined with a leaky-bucket-style minimum
 * inter-action delay (minDelay). Per-bucket transitions are atomic via a
 * single Redis Lua script (EVALSHA), preventing double-spend under
 * concurrency.
 * Solves the following problems across every bucket type
 * (e.g. session-creation, send-sms-verification-code-…,
 * send-voice-verification-code-…, check-verification-code-…):
 *
 * - Synchronized burst across bucket types: bound bucket to at most maxCapacity
 *   with actions inside a minDelay window, so multiple bucket types firing within ~1ms
 *   does not fan out to downstream services and trip rate limit
 * - Sustained N calls per day replay attack: long permitRegenerationPeriod
 *   throttles continous req against same bucket
 *
 *  */
public abstract class RedisLeakyBucketRateLimiter<K> implements RateLimiter<K> {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final RedisScript<Long> script;
    private final RedisLeakyBucketRateLimiterConfiguration configuration;

    public RedisLeakyBucketRateLimiter(
            StringRedisTemplate redisTemplate, Clock clock, RedisLeakyBucketRateLimiterConfiguration configuration) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.configuration = configuration;

        script = RedisScript.of(new ClassPathResource("validate-ratelimit.lua"), Long.class);
    }

    @Override
    public Optional<Instant> getNextActionTime(K key) {
        return Optional.of(clock.instant().plus(executeLuaScript(getBucketName(key), false)));
    }

    abstract String getBucketName(K key);

    abstract boolean shouldFailOpen();

    @Override
    public void checkRateLimit(K key) throws RateLimitExceededException {

        try {
            final Duration durationOfNextAction = executeLuaScript(getBucketName(key), true);
            if (durationOfNextAction.isPositive()) {
                throw new RateLimitExceededException(durationOfNextAction);
            }
        } catch (RuntimeException e) {
            if (shouldFailOpen()) {
                return;
            } else {
                throw new RateLimitExceededException(null);
            }
        }
    }

    private Duration executeLuaScript(final String key, final Boolean consumeToken) {

        List<String> keys = List.of(key);
        final String[] arguments = {
            String.valueOf(configuration.maxCapacity()),
            String.valueOf(configuration.permitRegenerationPeriod().toMillis()),
            String.valueOf(configuration.minDelay().toMillis()),
            String.valueOf(clock.instant().toEpochMilli()),
            String.valueOf(consumeToken)
        };

        return Duration.ofMillis(redisTemplate.execute(script, keys, (Object[]) arguments));
    }
}
