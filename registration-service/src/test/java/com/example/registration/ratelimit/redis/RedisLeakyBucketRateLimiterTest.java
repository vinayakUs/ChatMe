package com.example.registration.ratelimit.redis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RedisLeakyBucketRateLimiterConfiguration;

@Testcontainers
@DataRedisTest
class RedisLeakyBucketRateLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate; // real template, provided by Spring

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private Clock clock;
    private static final Instant CURRENT_TIME = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private RedisLeakyBucketRateLimiter<String> rateLimiter;

    private static final int MAX_PERMITS = 2;
    private static final Duration PERMIT_REGENERATION_PERIOD = Duration.ofMinutes(1);
    private static final Duration MIN_DELAY = Duration.ofSeconds(5);

    // Small concrete subclass, because the real class is abstract
    private static class TestRateLimiter extends RedisLeakyBucketRateLimiter<String> {
        private final boolean failOpen;

        TestRateLimiter(StringRedisTemplate template, Clock clock, boolean failOpen) {
            super(template, clock,
                    new RedisLeakyBucketRateLimiterConfiguration(
                            "test", MAX_PERMITS, PERMIT_REGENERATION_PERIOD, MIN_DELAY));
            this.failOpen = failOpen;
        }

        @Override
        String getBucketName(String key) {
            return key;
        }

        @Override
        boolean shouldFailOpen() {
            return failOpen;
        }
    }

    @BeforeEach
    void setUp() {
        // empty redis
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(CURRENT_TIME);

        rateLimiter = new TestRateLimiter(redisTemplate, clock, false);
    }

    @Test
    void testCheckRateLimit() {
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("test"), "A fresh key should succeed");
        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkRateLimit("test"),
                "Immediate checking should trigger a min cooldown delay.");

        when(clock.instant()).thenReturn(CURRENT_TIME.plus(MIN_DELAY));
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("test"), "After min cooldown delay it should succeed");
        when(clock.instant()).thenReturn(CURRENT_TIME.plus(MIN_DELAY.multipliedBy(2)));
        assertThrows(RateLimitExceededException.class, () -> rateLimiter.checkRateLimit("test"),
                "Both permits are used, so it should fail until permits regenerate");

    }

    @Test
    void testGetBucketName() {

    }

    @Test
    void testGetNextActionTime() {

    assertEquals(CURRENT_TIME, rateLimiter.getNextActionTime("test").orElseThrow(),"Action time for fresh bucket should be immediate");

    assertDoesNotThrow(()->rateLimiter.checkRateLimit("test"));

    assertEquals(Optional.of(CURRENT_TIME.plus(MIN_DELAY)), rateLimiter.getNextActionTime("test"),"action for just used bucket should be permitted to used after cooldown");

    when(clock.instant()).thenReturn(CURRENT_TIME.plus(MIN_DELAY));
    assertEquals(Optional.of(CURRENT_TIME.plus(MIN_DELAY)), rateLimiter.getNextActionTime("test"));
    //Consume the second permit
    assertDoesNotThrow(() -> rateLimiter.checkRateLimit("test"));

    final Long eps = Math.abs(rateLimiter.getNextActionTime("test").orElseThrow().toEpochMilli() - CURRENT_TIME.plus(PERMIT_REGENERATION_PERIOD).toEpochMilli());

    assertTrue(eps <= 1);


    }

    @Test
    void testShouldFailOpen() {

    }
}
