package com.example.registration.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RedisLeakyBucketRateLimiterConfiguration;

/**
 * Integration tests for {@link RedisLeakyBucketRateLimiter} against the Redis
 * instance configured in application.yml.
 *
 * <p>Every test writes only keys under {@link #KEY_PREFIX}, which {@link #cleanup()}
 * deletes afterwards. Time is supplied by a {@link MutableClock} injected through the
 * constructor, so no test sleeps or depends on wall-clock timing.
 *
 * <p>Set {@code -Dredis.test.skip=true} to skip if Redis is unreachable.
 */
class RedisLeakyBucketRateLimiterTest {

    private static final String KEY_PREFIX = "rl:test:";
    private static final Instant T0 = Instant.ofEpochMilli(1_700_000_000_000L);

    /** 3 permits, one regenerated per second, no minimum delay. */
    private static final RedisLeakyBucketRateLimiterConfiguration BURST =
            new RedisLeakyBucketRateLimiterConfiguration("burst", 3, Duration.ofSeconds(1), Duration.ZERO);

    /** 10 permits, one regenerated per second, but never closer together than 2s. */
    private static final RedisLeakyBucketRateLimiterConfiguration DELAYED =
            new RedisLeakyBucketRateLimiterConfiguration("delayed", 10, Duration.ofSeconds(1), Duration.ofSeconds(2));

    private StringRedisTemplate redis;
    private LettuceConnectionFactory connectionFactory;
    private final MutableClock clock = new MutableClock(T0);
    private final List<String> keysUsed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        yaml.afterPropertiesSet();
        final Properties props = yaml.getObject();
        assumeTrue(props != null, "application.yml not found");

        final String host = props.getProperty("spring.data.redis.host");
        final String port = props.getProperty("spring.data.redis.port");
        assumeTrue(host != null && port != null, "no spring.data.redis.* in application.yml");

        connectionFactory = new LettuceConnectionFactory(host, Integer.parseInt(port));
        final String password = props.getProperty("spring.data.redis.password");
        if (password != null) {
            connectionFactory.setPassword(password);
        }
        connectionFactory.afterPropertiesSet();

        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        assumeTrue(!Boolean.getBoolean("redis.test.skip"), "skipped via -Dredis.test.skip=true");
        assumeTrue(pingQuietly(), "Redis unreachable; re-run without -Dredis.test.skip=true");
    }

    @AfterEach
    void cleanup() {
        keysUsed.forEach(redis::delete);
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    // ---------------------------------------------------------------- permits

    @Test
    @DisplayName("first call on an unseen key is allowed")
    void firstCallIsAllowed() {
        assertThatCode(() -> limiter(BURST).checkRateLimit(key("fresh"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("permits are granted up to maxCapacity, then rejected")
    void burstIsCappedAtMaxCapacity() throws Exception {
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(BURST);
        final String key = key("burst");

        for (int i = 0; i < BURST.maxCapacity(); i++) {
            assertThatCode(() -> limiter.checkRateLimit(key))
                    .as("call %d should be allowed", i + 1)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.checkRateLimit(key))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfter())
                        .isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a spent permit regenerates once time advances")
    void permitRegeneratesOverTime() throws Exception {
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(BURST);
        final String key = key("regen");

        for (int i = 0; i < BURST.maxCapacity(); i++) {
            limiter.checkRateLimit(key);
        }
        assertThatThrownBy(() -> limiter.checkRateLimit(key)).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(1));

        assertThatCode(() -> limiter.checkRateLimit(key))
                .as("one permit should have regenerated after 1s")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkRateLimit(key))
                .as("only one permit regenerated, so the next call is throttled again")
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("a long idle period refills the bucket but never past maxCapacity")
    void bucketRefillsToCapacityButNoFurther() throws Exception {
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(BURST);
        final String key = key("idle");

        for (int i = 0; i < BURST.maxCapacity(); i++) {
            limiter.checkRateLimit(key);
        }

        clock.advance(Duration.ofHours(1)); // far more refill than capacity

        for (int i = 0; i < BURST.maxCapacity(); i++) {
            assertThatCode(() -> limiter.checkRateLimit(key))
                    .as("call %d after a long idle should be allowed", i + 1)
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> limiter.checkRateLimit(key)).isInstanceOf(RateLimitExceededException.class);
    }

    // -------------------------------------------------------------- min delay

    @Test
    @DisplayName("minDelay blocks a second call even when permits remain")
    void minDelayBlocksBackToBackCalls() throws Exception {
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(DELAYED);
        final String key = key("delay");

        assertThatCode(() -> limiter.checkRateLimit(key)).doesNotThrowAnyException();

        assertThatThrownBy(() -> limiter.checkRateLimit(key))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfter())
                        .as("caller must wait out the full minDelay, not just the permit")
                        .isEqualTo(Duration.ofSeconds(2)));

        clock.advance(Duration.ofSeconds(2));

        assertThatCode(() -> limiter.checkRateLimit(key))
                .as("allowed once the minDelay window has elapsed")
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- read only

    @Test
    @DisplayName("getNextActionTime reports the wait without consuming a permit")
    void peekDoesNotConsumePermit() throws Exception {
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(BURST);
        final String key = key("peek");

        for (int i = 0; i < BURST.maxCapacity(); i++) {
            limiter.checkRateLimit(key);
        }

        assertThat(limiter.getNextActionTime(key)).contains(T0.plusSeconds(1));

        assertThatThrownBy(() -> limiter.checkRateLimit(key))
                .as("peek must not have spent the permit it reported")
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfter())
                        .isEqualTo(Duration.ofSeconds(1)));
    }

    // ------------------------------------------------------------------ keys

    @Test
    @DisplayName("distinct keys hold independent buckets")
    void keysAreIndependent() throws Exception {
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(BURST);
        final String keyA = key("a");
        final String keyB = key("b");

        for (int i = 0; i < BURST.maxCapacity(); i++) {
            limiter.checkRateLimit(keyA);
        }

        assertThatThrownBy(() -> limiter.checkRateLimit(keyA)).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.checkRateLimit(keyB))
                .as("draining keyA must not throttle keyB")
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ concurrency

    @Test
    @DisplayName("concurrent calls on one key never overspend the bucket")
    void concurrentCallsCannotDoubleSpend() throws Exception {
        final int threads = 32;
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(BURST);
        final String key = key("race");

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger allowed = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    try {
                        limiter.checkRateLimit(key);
                        allowed.incrementAndGet();
                    } catch (RateLimitExceededException expected) {
                        // throttled, as intended
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("EVALSHA must serialise transitions; exactly maxCapacity calls may pass")
                .isEqualTo(BURST.maxCapacity());
    }

    // ------------------------------------------------------------ key expiry

    @Test
    @DisplayName("bucket outlives the longest window it still has to honour")
    void ttlCoversRegenAndCooldownWindows() throws Exception {
        // Regeneration needs 5 * 1s = 5s; the cooldown started at the same instant needs 10s.
        final RedisLeakyBucketRateLimiterConfiguration config =
                new RedisLeakyBucketRateLimiterConfiguration("ttl", 5, Duration.ofSeconds(1), Duration.ofSeconds(10));
        final RedisLeakyBucketRateLimiter<String> limiter = limiter(config);
        final String key = key("ttl");

        for (int i = 0; i < config.maxCapacity(); i++) {
            limiter.checkRateLimit(key);
        }

        final Long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis)
                .as("key must live until both the refill and the cooldown have elapsed, else the next "
                        + "caller finds it gone and gets a fresh full bucket, skipping the cooldown")
                .isNotNull()
                .isGreaterThanOrEqualTo(Duration.ofSeconds(15).toMillis());
    }

    // ----------------------------------------------------------------- helpers

    private RedisLeakyBucketRateLimiter<String> limiter(final RedisLeakyBucketRateLimiterConfiguration config) {
        return new TestRateLimiter(redis, clock, config);
    }

    /** Registers the key for cleanup so each test starts from a clean bucket. */
    private String key(final String suffix) {
        final String key = KEY_PREFIX + suffix;
        keysUsed.add(key);
        redis.delete(key);
        return key;
    }

    private boolean pingQuietly() {
        try {
            redis.getConnectionFactory().getConnection().ping();
            return true;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    /** Fixed clock that tests advance instead of sleeping. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(final Instant instant) {
            this.instant = instant;
        }

        void advance(final Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Minimal concrete limiter; production subclasses supply a real bucket name and policy. */
    private static final class TestRateLimiter extends RedisLeakyBucketRateLimiter<String> {

        TestRateLimiter(final StringRedisTemplate redisTemplate, final Clock clock,
                final RedisLeakyBucketRateLimiterConfiguration configuration) {
            super(redisTemplate, clock, configuration);
        }

        @Override
        String getBucketName(final String key) {
            return key;
        }

        @Override
        boolean shouldFailOpen() {
            return false;
        }
    }
}