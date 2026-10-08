# Rate Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A rate limiter with two interchangeable stores (in-memory and Redis) that answers "may this caller take this action?" and "when may they next take it?", wired into `RegistraionSessionMetadata`'s `may_request_sms` / `next_sms_seconds` / `may_code_check` / `next_code_check_seconds` fields.

**Architecture:** A leaky-bucket algorithm implemented twice — once over a `ConcurrentHashMap` with lazy eviction, once as an atomic Redis Lua script — behind one `RateLimiter<K>` interface with two methods: a non-mutating `getTimeOfNextAction` and a consuming `checkRateLimit`. One bean exists per `RegistrationAction`, selected by the caller via a switch. Limits live on the enum; bucket keys are `<action>::<E164>` so creating a new session does not reset a caller's allowance.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Data Redis (Lettuce), Micrometer, JUnit 5, Testcontainers, libphonenumber.

**Spec:** `docs/superpowers/specs/2026-10-07-ratelimiter-design.md`

## Global Constraints

- Bucket key format is exactly `<action-key-prefix>::<E164>`, e.g. `request-sms::+15551230123`. The separator is a double colon.
- `getTimeOfNextAction` MUST NOT mutate limiter state. This is the contract that lets a response be built without consuming allowance.
- Every store implementation MUST inject `java.time.Clock` and MUST NOT call `Instant.now()`, `System.currentTimeMillis()`, or `Clock.systemDefaultZone()` directly. Tests depend on this.
- Redis failures MUST fail open: allow the action and increment a Micrometer counter named `ratelimiter.failed.open` tagged `action=<ENUM_NAME>`.
- Package root is `com.example.registration.ratelimit`. Redis-specific classes live in `com.example.registration.ratelimit.redis`.
- `RedisLeakyBucketRateLimiter` and `InMemoryLeakyBucketRateLimiter` are both generic over the key type `K` and both derive their storage key from `protected String getBucketKey(K key)`. This symmetry is what lets one contract test cover both.
- Limits are fixed on `RegistrationAction`. Do not add a configuration-properties class for them.
- Existing files `RegistrationService.java` and `RegistrationGrpcService.java` are modified in Task 5 only; Tasks 1–4 add new files without touching them.
- All new code uses 4-space indentation to match the existing sources.

## Review Focus

Five inputs or conditions the spec implies but no task's happy-path test covers, most likely to bite first:

1. **Two spellings of one phone number.** `+15551230123` and a `PhoneNumber` parsed from `"1 (555) 123-0123"` must land in the same bucket. If they don't, rate limiting is trivially bypassed by changing punctuation.
2. **A reported time in the past.** `next_sms_seconds` computed as a negative `Duration` would encode into the protobuf `uint64` and throw. Must clamp to 0, and `may_request_sms` must be true.
3. **Redis returning a null script result.** `StringRedisTemplate.execute` can return `null`; unboxing it throws a bare `NullPointerException` with no context about which bucket failed.
4. **Building a response twice.** `buildRegistraionSessionResponse` is called for every caller. It must not consume permits, or a user polling their session exhausts their own allowance.
5. **Concurrent identical requests.** Without atomic updates, two simultaneous requests for the same number can both observe a spare permit and both consume it, exceeding the configured rate.

---

### Task 1: Core types and dependencies

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/example/registration/ratelimit/RateLimiter.java`
- Create: `src/main/java/com/example/registration/ratelimit/RegistrationAction.java`
- Create: `src/main/java/com/example/registration/ratelimit/RateLimitExceededException.java`
- Test: `src/test/java/com/example/registration/ratelimit/RegistrationActionTest.java`
- Test: `src/test/java/com/example/registration/ratelimit/RateLimitExceededExceptionTest.java`

**Interfaces:**
- Consumes: nothing. This task defines everything downstream tasks use.
- Produces:
  - `RateLimiter<K>` with `Optional<Instant> getTimeOfNextAction(K key)` and `void checkRateLimit(K key)`
  - `RegistrationAction` enum: `REQUEST_SMS`, `CODE_CHECK`; accessors `getKeyPrefix()`, `getPermits()`, `getPermitRegenerationPeriod()`, `getMinDelay()`
  - `RateLimitExceededException extends RuntimeException` with `Optional<Duration> getRetryAfterDuration()`, `Optional<RegistrationSession> getRegistrationSession()`, `void setRegistrationSession(...)`

- [ ] **Step 1: Add dependencies to `pom.xml`**

Insert these before the closing `</dependencies>` tag, alongside the existing test dependency:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-redis</artifactId>
		</dependency>

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>

		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>

		<dependency>
			<groupId>com.redis</groupId>
			<artifactId>testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
```

`com.redis:testcontainers` is version-managed by the Spring Boot 4.1.1 BOM. If Maven reports "dependencies.dependency.version is missing", add `<version>2.2.4</version>` to that one dependency.

- [ ] **Step 2: Write the failing test for `RegistrationAction`**

```java
package com.example.registration.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RegistrationActionTest {

    @Test
    void everyActionHasUsableLimits() {
        for (final RegistrationAction action : RegistrationAction.values()) {
            assertTrue(action.getPermits() >= 1,
                    () -> action + " must allow at least one attempt per window");
            assertTrue(action.getPermitRegenerationPeriod().toMillis() > 0,
                    () -> action + " must have a positive regeneration period");
            assertTrue(action.getMinDelay().toMillis() >= 0,
                    () -> action + " must not have a negative cooldown");
            assertTrue(action.getKeyPrefix().matches("[a-z0-9-]+"),
                    () -> action + " key prefix must be lower-case and dash-separated: " + action.getKeyPrefix());
        }
    }

    @Test
    void keyPrefixesAreDistinct() {
        assertEquals(2, RegistrationAction.values().length,
                "every action must have its own bucket namespace");
        assertEquals("request-sms", RegistrationAction.REQUEST_SMS.getKeyPrefix());
        assertEquals("code-check", RegistrationAction.CODE_CHECK.getKeyPrefix());
    }

    @Test
    void limitsMatchTheDesign() {
        assertEquals(3, RegistrationAction.REQUEST_SMS.getPermits());
        assertEquals(Duration.ofMinutes(5), RegistrationAction.REQUEST_SMS.getPermitRegenerationPeriod());
        assertEquals(Duration.ofSeconds(30), RegistrationAction.REQUEST_SMS.getMinDelay());

        assertEquals(5, RegistrationAction.CODE_CHECK.getPermits());
        assertEquals(Duration.ofMinutes(1), RegistrationAction.CODE_CHECK.getPermitRegenerationPeriod());
        assertEquals(Duration.ofSeconds(5), RegistrationAction.CODE_CHECK.getMinDelay());
    }
}
```

- [ ] **Step 3: Write the failing test for `RateLimitExceededException`**

```java
package com.example.registration.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class RateLimitExceededExceptionTest {

    @Test
    void carriesTheRetryAfterDuration() {
        final RateLimitExceededException e = new RateLimitExceededException(Duration.ofSeconds(42));
        assertEquals(Optional.of(Duration.ofSeconds(42)), e.getRetryAfterDuration());
    }

    @Test
    void toleratesAnAbsentRetryAfterDuration() {
        final RateLimitExceededException e = new RateLimitExceededException(null);
        assertNull(e.getRetryAfterDuration().orElse(null));
    }

    @Test
    void toleratesAnAbsentSession() {
        assertTrue(new RateLimitExceededException(Duration.ZERO).getRegistrationSession().isEmpty());
    }

    @Test
    void canCarryTheSessionInPlay() {
        final RateLimitExceededException e = new RateLimitExceededException(Duration.ofSeconds(5));
        assertTrue(e.getRegistrationSession().isEmpty());

        // RegistrationSession is a protobuf type; an empty instance stands in for a real one here.
        e.setRegistrationSession(com.example.registration.session.RegistrationSession.newBuilder().build());

        assertTrue(e.getRegistrationSession().isPresent());
    }

    @Test
    void isUnchecked() {
        assertThrows(RateLimitExceededException.class, () -> {
            throw new RateLimitExceededException(Duration.ZERO);
        });
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='RegistrationActionTest,RateLimitExceededExceptionTest'`
Expected: compilation failure — `cannot find symbol: class RegistrationAction`.

- [ ] **Step 5: Write `RateLimiter.java`**

```java
package com.example.registration.ratelimit;

import java.time.Instant;
import java.util.Optional;

/**
 * A rate limiter limits how many times in a period an action, identified by a given key, may be taken.
 *
 * @param <K> the type of key that identifies a rate-limited action
 */
public interface RateLimiter<K> {

    /**
     * Returns the next time at which the rate-limited action may be taken. If the returned time is before or equal to
     * the current time, the action may be taken immediately.
     *
     * <p>Implementations MUST NOT change rate limiter state. Callers must not be penalised for asking.
     *
     * @param key a key identifying the action to be taken
     * @return the next time the action may be taken, or empty if no amount of waiting will permit it and the caller
     * must take some other action to proceed
     */
    Optional<Instant> getTimeOfNextAction(K key);

    /**
     * Consumes one permit for the given key, if the rate limit permits.
     *
     * @param key a key identifying the action to be taken
     * @throws RateLimitExceededException if the caller must wait before taking the action
     */
    void checkRateLimit(K key) throws RateLimitExceededException;
}
```

- [ ] **Step 6: Write `RegistrationAction.java`**

```java
package com.example.registration.ratelimit;

import java.time.Duration;

/**
 * The actions that can be rate-limited during registration, and the limits that apply to each.
 */
public enum RegistrationAction {

    /** Sending a verification code to the number being registered. */
    REQUEST_SMS("request-sms", 3, Duration.ofMinutes(5), Duration.ofSeconds(30)),

    /** Submitting a verification code for comparison against the stored one. */
    CODE_CHECK("code-check", 5, Duration.ofMinutes(1), Duration.ofSeconds(5));

    private final String keyPrefix;
    private final int permits;
    private final Duration permitRegenerationPeriod;
    private final Duration minDelay;

    RegistrationAction(
            final String keyPrefix,
            final int permits,
            final Duration permitRegenerationPeriod,
            final Duration minDelay) {

        this.keyPrefix = keyPrefix;
        this.permits = permits;
        this.permitRegenerationPeriod = permitRegenerationPeriod;
        this.minDelay = minDelay;
    }

    /** Namespace for this action's buckets, so different actions never share one. */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /** How many attempts are permitted before the bucket is empty. */
    public int getPermits() {
        return permits;
    }

    /** How long it takes for one permit to regenerate. */
    public Duration getPermitRegenerationPeriod() {
        return permitRegenerationPeriod;
    }

    /** The cooldown enforced between attempts, even while permits remain. */
    public Duration getMinDelay() {
        return minDelay;
    }
}
```

- [ ] **Step 7: Write `RateLimitExceededException.java`**

```java
package com.example.registration.ratelimit;

import java.time.Duration;
import java.util.Optional;

import com.example.registration.session.RegistrationSession;

/**
 * Indicates that an action was not permitted because the caller has attempted it too frequently. Callers receiving this
 * exception may retry after the duration in {@link #getRetryAfterDuration()}.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfterDuration;
    private RegistrationSession registrationSession;

    public RateLimitExceededException(final Duration retryAfterDuration) {
        super("Rate limit exceeded", null, false, false);
        this.retryAfterDuration = retryAfterDuration;
    }

    public RateLimitExceededException(
            final Duration retryAfterDuration, final RegistrationSession registrationSession) {

        this(retryAfterDuration);
        this.registrationSession = registrationSession;
    }

    /**
     * Returns the next time the blocked action might succeed.
     *
     * @return how long to wait, or empty if the limiter could not determine a wait
     */
    public Optional<Duration> getRetryAfterDuration() {
        return Optional.ofNullable(retryAfterDuration);
    }

    public void setRegistrationSession(final RegistrationSession registrationSession) {
        this.registrationSession = registrationSession;
    }

    /**
     * Returns the registration session associated with this exception, if any. The session may carry additional
     * rate-limiting state worth returning to the caller.
     */
    public Optional<RegistrationSession> getRegistrationSession() {
        return Optional.ofNullable(registrationSession);
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='RegistrationActionTest,RateLimitExceededExceptionTest'`
Expected: PASS — 8 tests, 0 failures.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/com/example/registration/ratelimit src/test/java/com/example/registration/ratelimit
git commit -m "feat(ratelimit): add RateLimiter interface, RegistrationAction, RateLimitExceededException"
```

---

### Task 2: In-memory store and the shared contract test

**Files:**
- Create: `src/main/java/com/example/registration/ratelimit/InMemoryLeakyBucketRateLimiter.java`
- Test: `src/test/java/com/example/registration/ratelimit/MutableClock.java`
- Test: `src/test/java/com/example/registration/ratelimit/RateLimiterContractTest.java`
- Test: `src/test/java/com/example/registration/ratelimit/InMemoryLeakyBucketRateLimiterTest.java`

**Interfaces:**
- Consumes: `RateLimiter<K>`, `RegistrationAction`, `RateLimitExceededException` from Task 1.
- Produces:
  - `InMemoryLeakyBucketRateLimiter<K>` constructor `(Clock clock, RegistrationAction action)`, `protected String getBucketKey(K key)`, `protected String getKeyPrefix()`, package-private `int trackedBucketCount()`
  - `RateLimiterContractTest` abstract class — Task 3 extends it
  - `MutableClock` — `setInstant(Instant)`, `advance(Duration)`

- [ ] **Step 1: Write `MutableClock.java`**

```java
package com.example.registration.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} whose time moves only when a test moves it.
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock(final Instant instant) {
        this(instant, ZoneId.of("UTC"));
    }

    private MutableClock(final Instant instant, final ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void setInstant(final Instant instant) {
        this.instant = instant;
    }

    public void advance(final Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return new MutableClock(this.instant, zone);
    }

    @Override
    public Instant instant() {
        return this.instant;
    }
}
```

- [ ] **Step 2: Write the contract test**

This is the test that guarantees both stores behave identically. Every later behavioural assertion belongs here, not in a store-specific test.

```java
package com.example.registration.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link RateLimiter} implementation must exhibit identically. Subclasses supply only a factory.
 */
public abstract class RateLimiterContractTest {

    protected static final String KEY = "+15551230123";
    protected static final String OTHER_KEY = "+15559999999";

    protected static final Instant START =
            Instant.parse("2026-01-01T00:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    protected MutableClock clock;

    /** Builds a fresh, empty limiter for the supplied action. */
    protected abstract RateLimiter<String> createLimiter(RegistrationAction action);

    @BeforeEach
    void setUpClock() {
        this.clock = new MutableClock(START);
    }

    protected static Duration fullyRegeneratedAfter(final RegistrationAction action) {
        return action.getPermitRegenerationPeriod()
                .multipliedBy(action.getPermits())
                .plus(action.getMinDelay());
    }

    @Test
    void freshKeyIsPermittedImmediately() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);

        assertEquals(Optional.of(START), limiter.getTimeOfNextAction(KEY),
                "a key that has never been seen must be permitted now");

        assertDoesNotThrow(() -> limiter.checkRateLimit(KEY));
    }

    @Test
    void permitsAreExhaustedAfterTheConfiguredCount() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);
        final int permits = RegistrationAction.REQUEST_SMS.getPermits();

        for (int i = 0; i < permits; i++) {
            final int permitNumber = i + 1;
            assertDoesNotThrow(() -> limiter.checkRateLimit(KEY),
                    "permit " + permitNumber + " of " + permits + " must be granted");
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }

        final RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkRateLimit(KEY),
                "the attempt after the last permit must be denied");

        assertTrue(e.getRetryAfterDuration().isPresent(),
                "a denial must say how long to wait");
    }

    @Test
    void queryingDoesNotConsumePermits() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);
        final int permits = RegistrationAction.REQUEST_SMS.getPermits();

        for (int i = 0; i < 20; i++) {
            assertEquals(Optional.of(START), limiter.getTimeOfNextAction(KEY),
                    "repeated queries must not change state");
        }

        for (int i = 0; i < permits; i++) {
            final int permitNumber = i + 1;
            assertDoesNotThrow(() -> limiter.checkRateLimit(KEY),
                    "permit " + permitNumber + " must survive 20 prior queries");
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }
    }

    @Test
    void cooldownBlocksAnImmediateRepeatEvenWithPermitsRemaining() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);

        assertDoesNotThrow(() -> limiter.checkRateLimit(KEY));

        final RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkRateLimit(KEY),
                "an immediate repeat must be blocked despite spare permits");

        assertEquals(Optional.of(RegistrationAction.REQUEST_SMS.getMinDelay()),
                e.getRetryAfterDuration(),
                "the wait for a cooldown denial is the cooldown itself");
    }

    @Test
    void permitsRegenerateOverTime() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);
        final int permits = RegistrationAction.REQUEST_SMS.getPermits();

        for (int i = 0; i < permits; i++) {
            assertDoesNotThrow(() -> limiter.checkRateLimit(KEY));
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }

        assertThrows(RateLimitExceededException.class, () -> limiter.checkRateLimit(KEY));

        this.clock.advance(RegistrationAction.REQUEST_SMS.getPermitRegenerationPeriod());

        assertDoesNotThrow(() -> limiter.checkRateLimit(KEY),
                "one permit must regenerate after one regeneration period");
    }

    @Test
    void theReportedWaitIsWhenTheActionIsActuallyPermitted() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);
        final int permits = RegistrationAction.REQUEST_SMS.getPermits();

        for (int i = 0; i < permits; i++) {
            assertDoesNotThrow(() -> limiter.checkRateLimit(KEY));
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }

        final Optional<Instant> next = limiter.getTimeOfNextAction(KEY);
        assertTrue(next.isPresent(), "an exhausted bucket must report a time");
        assertTrue(next.get().isAfter(START), "an exhausted bucket must report a future time");

        this.clock.setInstant(next.get());

        assertDoesNotThrow(() -> limiter.checkRateLimit(KEY),
                "the action must genuinely be permitted at the reported time");
    }

    @Test
    void distinctKeysAreIndependent() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);
        final int permits = RegistrationAction.REQUEST_SMS.getPermits();

        for (int i = 0; i < permits; i++) {
            assertDoesNotThrow(() -> limiter.checkRateLimit(KEY));
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }

        assertThrows(RateLimitExceededException.class, () -> limiter.checkRateLimit(KEY));

        assertDoesNotThrow(() -> limiter.checkRateLimit(OTHER_KEY),
                "an exhausted key must not exhaust a different key");
    }

    @Test
    void distinctActionsHaveDistinctBuckets() {
        final RateLimiter<String> sms = createLimiter(RegistrationAction.REQUEST_SMS);
        final RateLimiter<String> codeCheck = createLimiter(RegistrationAction.CODE_CHECK);
        final int permits = RegistrationAction.REQUEST_SMS.getPermits();

        for (int i = 0; i < permits; i++) {
            assertDoesNotThrow(() -> sms.checkRateLimit(KEY));
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }

        assertThrows(RateLimitExceededException.class, () -> sms.checkRateLimit(KEY));

        assertEquals(Optional.of(START), codeCheck.getTimeOfNextAction(KEY),
                "exhausting the SMS bucket must leave the code-check bucket untouched");
    }
}
```

- [ ] **Step 3: Write the in-memory test, extending the contract test**

```java
package com.example.registration.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class InMemoryLeakyBucketRateLimiterTest extends RateLimiterContractTest {

    @Override
    protected RateLimiter<String> createLimiter(final RegistrationAction action) {
        return new InMemoryLeakyBucketRateLimiter<>(this.clock, action);
    }

    @Test
    void concurrentChecksDoNotOverGrant() throws Exception {
        final InMemoryLeakyBucketRateLimiter<String> limiter =
                new InMemoryLeakyBucketRateLimiter<>(this.clock, RegistrationAction.REQUEST_SMS);

        final int attempts = 200;
        final ExecutorService pool = Executors.newFixedThreadPool(16);
        final CountDownLatch startLine = new CountDownLatch(1);
        final AtomicInteger granted = new AtomicInteger();

        try {
            final List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await();
                    try {
                        limiter.checkRateLimit(KEY);
                        granted.incrementAndGet();
                    } catch (final RateLimitExceededException denied) {
                        // expected for all but the first caller
                    }
                    return null;
                }));
            }

            startLine.countDown();

            for (final Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // The clock is frozen, so the cooldown permits exactly one caller. A limiter that reads and writes
        // without atomically would let several through, which is what this pins down.
        assertEquals(1, granted.get(),
                "concurrent checks on one key must not over-grant under a frozen clock");
    }

    @Test
    void sweepEvictsFullyRegeneratedBuckets() {
        final InMemoryLeakyBucketRateLimiter<String> limiter =
                new InMemoryLeakyBucketRateLimiter<>(this.clock, RegistrationAction.REQUEST_SMS);

        for (int i = 0; i < 999; i++) {
            limiter.checkRateLimit("+1555" + i);
        }

        assertEquals(999, limiter.trackedBucketCount(),
                "every distinct key must be tracked until it is swept");

        this.clock.advance(fullyRegeneratedAfter(RegistrationAction.REQUEST_SMS));

        // This is the 1000th operation, which triggers the sweep.
        limiter.checkRateLimit("+1555-new");

        assertEquals(1, limiter.trackedBucketCount(),
                "regenerated buckets must be evicted so the map stays bounded");
    }

    @Test
    void aDifferentSpellingOfTheSameNumberSharesABucket() {
        final InMemoryLeakyBucketRateLimiter<com.google.i18n.phonenumbers.Phonenumber.PhoneNumber> limiter =
                new InMemoryLeakyBucketRateLimiter<>(this.clock, RegistrationAction.REQUEST_SMS);

        final com.google.i18n.phonenumbers.PhoneNumberUtil util =
                com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance();

        final com.google.i18n.phonenumbers.Phonenumber.PhoneNumber canonical =
                util.parse("+15551230123", null);
        final com.google.i18n.phonenumbers.Phonenumber.PhoneNumber punctuated =
                util.parse("1 (555) 123-0123", "US");

        for (int i = 0; i < RegistrationAction.REQUEST_SMS.getPermits(); i++) {
            assertDoesNotThrow(() -> limiter.checkRateLimit(canonical));
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }

        assertThrows(RateLimitExceededException.class, () -> limiter.checkRateLimit(canonical));

        assertThrows(RateLimitExceededException.class, () -> limiter.checkRateLimit(punctuated),
                "a differently punctuated number must not evade an exhausted bucket");
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=InMemoryLeakyBucketRateLimiterTest`
Expected: compilation failure — `cannot find symbol: class InMemoryLeakyBucketRateLimiter`.

- [ ] **Step 5: Write `InMemoryLeakyBucketRateLimiter.java`**

```java
package com.example.registration.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A leaky-bucket rate limiter backed by an in-process map. Suitable for a single instance, or for tests.
 *
 * <p>Buckets are evicted once fully regenerated, mirroring the expiry the Redis-backed store performs in Redis
 * itself, so the two stores stay observationally equivalent.
 *
 * @param <K> the type of key that identifies a rate-limited action
 */
public class InMemoryLeakyBucketRateLimiter<K> implements RateLimiter<K> {

    /** How many operations pass between eviction sweeps. */
    private static final long SWEEP_INTERVAL_OPERATIONS = 1_000L;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();
    private final Clock clock;
    private final String keyPrefix;
    private final int bucketSize;
    private final long regenerationMillis;
    private final long minDelayMillis;

    public InMemoryLeakyBucketRateLimiter(final Clock clock, final RegistrationAction action) {
        this.clock = clock;
        this.keyPrefix = action.getKeyPrefix();
        this.bucketSize = action.getPermits();
        this.regenerationMillis = action.getPermitRegenerationPeriod().toMillis();
        this.minDelayMillis = action.getMinDelay().toMillis();
    }

    /** Namespace for this action's buckets. */
    protected String getKeyPrefix() {
        return this.keyPrefix;
    }

    /** The map key for a caller. Override to normalise, e.g. format a phone number as E.164. */
    protected String getBucketKey(final K key) {
        return key.toString();
    }

    @Override
    public Optional<Instant> getTimeOfNextAction(final K key) {
        final long now = this.clock.millis();
        final Bucket bucket = this.buckets.get(getBucketKey(key));

        if (bucket == null) {
            return Optional.of(Instant.ofEpochMilli(now));
        }

        return Optional.of(Instant.ofEpochMilli(now + waitMillis(bucket, now)));
    }

    @Override
    public void checkRateLimit(final K key) {
        final long now = this.clock.millis();

        // compute() serialises concurrent updates for one key. Its lambda must not return null, so the decision is
        // carried out to here and the exception is thrown outside the map.
        final long[] waitMillis = {-1L};

        this.buckets.compute(getBucketKey(key), (mapKey, existing) -> {
            final Bucket bucket = existing == null ? new Bucket(this.bucketSize, now) : existing;

            if (availablePermits(bucket, now) >= 1 && cooldownRemainingMillis(bucket, now) <= 0) {
                bucket.permitsRemaining = availablePermits(bucket, now) - 1;
                bucket.lastUpdateMillis = now;
            } else {
                waitMillis[0] = waitMillis(bucket, now);
            }

            return bucket;
        });

        if (waitMillis[0] > 0) {
            throw new RateLimitExceededException(Duration.ofMillis(waitMillis[0]));
        }

        maybeSweep();
    }

    private double availablePermits(final Bucket bucket, final long now) {
        final double elapsedMillis = now - bucket.lastUpdateMillis;

        return Math.min(this.bucketSize,
                bucket.permitsRemaining + (elapsedMillis / (double) this.regenerationMillis));
    }

    private long cooldownRemainingMillis(final Bucket bucket, final long now) {
        return bucket.lastUpdateMillis + this.minDelayMillis - now;
    }

    private long waitMillis(final Bucket bucket, final long now) {
        final long untilPermit =
                (long) Math.ceil((1 - availablePermits(bucket, now)) * this.regenerationMillis);

        return Math.max(untilPermit, cooldownRemainingMillis(bucket, now));
    }

    private void maybeSweep() {
        if (this.operations.incrementAndGet() % SWEEP_INTERVAL_OPERATIONS != 0) {
            return;
        }

        final long now = this.clock.millis();
        final long fullyRegeneratedAfter =
                (long) this.bucketSize * this.regenerationMillis + this.minDelayMillis;

        this.buckets.entrySet().removeIf(entry -> now - entry.getValue().lastUpdateMillis >= fullyRegeneratedAfter);
    }

    /** Visible for testing: how many buckets are currently held. */
    int trackedBucketCount() {
        return this.buckets.size();
    }

    private static final class Bucket {

        private double permitsRemaining;
        private long lastUpdateMillis;

        private Bucket(final double permitsRemaining, final long lastUpdateMillis) {
            this.permitsRemaining = permitsRemaining;
            this.lastUpdateMillis = lastUpdateMillis;
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=InMemoryLeakyBucketRateLimiterTest`
Expected: PASS — 11 tests, 0 failures (8 inherited from the contract test, 3 specific).

- [ ] **Step 7: Run the whole suite to check nothing regressed**

Run: `./mvnw -q test`
Expected: PASS. If `RegistrationServiceApplicationTests` fails, it was already failing before this change — check with `git stash` before assuming otherwise.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/registration/ratelimit/InMemoryLeakyBucketRateLimiter.java src/test/java/com/example/registration/ratelimit
git commit -m "feat(ratelimit): add in-memory leaky bucket store and the shared contract test"
```

---

### Task 3: Redis store

**Files:**
- Create: `src/main/resources/validate-rate-limit.lua`
- Create: `src/main/java/com/example/registration/ratelimit/redis/RedisLeakyBucketRateLimiter.java`
- Test: `src/test/java/com/example/registration/ratelimit/redis/RedisLeakyBucketRateLimiterTest.java`
- Test: `src/test/java/com/example/registration/ratelimit/redis/RedisLeakyBucketRateLimiterFailOpenTest.java`

**Interfaces:**
- Consumes: `RateLimiter<K>`, `RegistrationAction`, `RateLimitExceededException` from Task 1; `RateLimiterContractTest` from Task 2.
- Produces: `RedisLeakyBucketRateLimiter<K>` constructor `(StringRedisTemplate redis, Clock clock, MeterRegistry meterRegistry, RegistrationAction action)`, `protected String getBucketKey(K key)`, `protected String getKeyPrefix()`. Task 4 subclasses it for `PhoneNumber`.

- [ ] **Step 1: Write the Lua script**

Create `src/main/resources/validate-rate-limit.lua`:

```lua
-- A leaky-bucket rate limiter. Returns the number of milliseconds until the action may be taken;
-- 0 means it may be taken now.
--
-- Run read-only (consumePermits = "false") to ask when the action will next be permitted without
-- consuming a permit, or read/write ("true") to consume one.

local bucketId = KEYS[1]

local bucketSize = tonumber(ARGV[1])
local permitRegenerationMillis = tonumber(ARGV[2])
local minDelayMillis = tonumber(ARGV[3])
local currentTimeMillis = tonumber(ARGV[4])
local consumePermits = string.lower(ARGV[5]) == "true"
local requestedAmount = 1

local PERMITS_REMAINING_FIELD = "p"
local TIME_FIELD = "t"

local permitsRemaining
local lastUpdateTimeMillis
local remainingCooldown

if redis.call("EXISTS", bucketId) == 1 then
    local permitsRemainingStr, lastUpdateTimeMillisStr =
        unpack(redis.call("HMGET", bucketId, PERMITS_REMAINING_FIELD, TIME_FIELD))

    permitsRemaining = tonumber(permitsRemainingStr)
    lastUpdateTimeMillis = tonumber(lastUpdateTimeMillisStr)
    remainingCooldown = lastUpdateTimeMillis + minDelayMillis - currentTimeMillis
else
    permitsRemaining = bucketSize
    lastUpdateTimeMillis = currentTimeMillis
    remainingCooldown = 0
end

local elapsedTime = currentTimeMillis - lastUpdateTimeMillis
local availableAmount = math.min(
    bucketSize,
    permitsRemaining + (elapsedTime / permitRegenerationMillis))

if availableAmount >= requestedAmount and remainingCooldown <= 0 then
    if consumePermits then
        permitsRemaining = availableAmount - requestedAmount
        lastUpdateTimeMillis = currentTimeMillis

        local permitsUsed = bucketSize - permitsRemaining

        -- Expire the key once the bucket has had long enough to refill completely. A full bucket
        -- carries no information, so leaving it to expire is equivalent to deleting it now.
        if permitsUsed > 0 then
            local ttlMillis = math.max(remainingCooldown,
                math.ceil(permitsUsed * permitRegenerationMillis))

            redis.call("HSET", bucketId,
                PERMITS_REMAINING_FIELD, permitsRemaining,
                TIME_FIELD, lastUpdateTimeMillis)
            redis.call("PEXPIRE", bucketId, ttlMillis)
        else
            redis.call("DEL", bucketId)
        end
    end

    return 0
end

local permitRegenerationTime =
    math.ceil((requestedAmount - availableAmount) * permitRegenerationMillis)

return math.max(permitRegenerationTime, remainingCooldown)
```

- [ ] **Step 2: Write the failing fail-open test**

This needs no Redis, so it lives in its own class rather than behind the Docker-gated one.

```java
package com.example.registration.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.example.registration.ratelimit.MutableClock;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.RegistrationAction;

class RedisLeakyBucketRateLimiterFailOpenTest {

    private MutableClock clock;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        this.clock = new MutableClock(RateLimiterContractTestAccess.START);
        this.meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void allowsTheActionWhenRedisIsUnreachable() {
        @SuppressWarnings("unchecked")
        final StringRedisTemplate failing = mock(StringRedisTemplate.class);

        when(failing.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        assertDoesNotThrow(() -> createLimiter(failing).checkRateLimit("+15551230123"),
                "a Redis outage must not block registration");
    }

    @Test
    void countsEveryFailOpen() {
        @SuppressWarnings("unchecked")
        final StringRedisTemplate failing = mock(StringRedisTemplate.class);

        when(failing.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        final RateLimiter<String> limiter = createLimiter(failing);

        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> limiter.checkRateLimit("+15551230123"));
        }

        assertEquals(3.0,
                this.meterRegistry.counter("ratelimiter.failed.open", "action", "REQUEST_SMS").count(),
                0.001,
                "every fail-open must be visible in metrics");
    }

    @Test
    void reportsContextWhenTheScriptReturnsNothing() {
        @SuppressWarnings("unchecked")
        final StringRedisTemplate empty = mock(StringRedisTemplate.class);

        when(empty.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        final IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> createLimiter(empty).getTimeOfNextAction("+15551230123"));

        assertEquals(true, e.getMessage().contains("request-sms::+15551230123"),
                "the failure must name the bucket that produced no result");
    }

    private RateLimiter<String> createLimiter(final StringRedisTemplate redis) {
        return new RedisLeakyBucketRateLimiter<>(
                redis, this.clock, this.meterRegistry, RegistrationAction.REQUEST_SMS) {

            @Override
            protected String getBucketKey(final String key) {
                return getKeyPrefix() + "::" + key;
            }
        };
    }

    /** Shares the contract test's fixed start time without making the contract test's fields public. */
    static final class RateLimiterContractTestAccess {
        static final java.time.Instant START = java.time.Instant.parse("2026-01-01T00:00:00Z");

        private RateLimiterContractTestAccess() {
        }
    }
}
```

- [ ] **Step 3: Run the fail-open test to verify it fails**

Run: `./mvnw -q test -Dtest=RedisLeakyBucketRateLimiterFailOpenTest`
Expected: compilation failure — `cannot find symbol: class RedisLeakyBucketRateLimiter`.

- [ ] **Step 4: Write `RedisLeakyBucketRateLimiter.java`**

```java
package com.example.registration.ratelimit.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.RegistrationAction;

/**
 * A leaky-bucket rate limiter backed by Redis, so limits hold across every instance of the service.
 *
 * <p>The bucket arithmetic lives in {@code validate-rate-limit.lua} so that reading the next action time and
 * consuming a permit are each a single atomic round trip.
 *
 * @param <K> the type of key that identifies a rate-limited action
 */
public class RedisLeakyBucketRateLimiter<K> implements RateLimiter<K> {

    private static final DefaultRedisScript<Long> SCRIPT = loadScript();

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String keyPrefix;
    private final int permits;
    private final long regenerationMillis;
    private final long minDelayMillis;
    private final Counter failedOpenCounter;

    public RedisLeakyBucketRateLimiter(
            final StringRedisTemplate redis,
            final Clock clock,
            final MeterRegistry meterRegistry,
            final RegistrationAction action) {

        this.redis = redis;
        this.clock = clock;
        this.keyPrefix = action.getKeyPrefix();
        this.permits = action.getPermits();
        this.regenerationMillis = action.getPermitRegenerationPeriod().toMillis();
        this.minDelayMillis = action.getMinDelay().toMillis();
        this.failedOpenCounter =
                meterRegistry.counter("ratelimiter.failed.open", "action", action.name());
    }

    private static DefaultRedisScript<Long> loadScript() {
        final DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("validate-rate-limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** Namespace for this action's buckets. */
    protected String getKeyPrefix() {
        return this.keyPrefix;
    }

    /** The Redis key for a caller. Override to normalise, e.g. format a phone number as E.164. */
    protected String getBucketKey(final K key) {
        return key.toString();
    }

    @Override
    public Optional<Instant> getTimeOfNextAction(final K key) {
        return Optional.of(Instant.ofEpochMilli(this.clock.millis() + execute(key, false)));
    }

    @Override
    public void checkRateLimit(final K key) {
        final long waitMillis;

        try {
            waitMillis = execute(key, true);
        } catch (final RedisConnectionFailureException unreachable) {
            // Fail open: registration keeps working when Redis is down. The counter makes the gap visible.
            this.failedOpenCounter.increment();
            return;
        }

        if (waitMillis > 0) {
            throw new RateLimitExceededException(Duration.ofMillis(waitMillis));
        }
    }

    private long execute(final K key, final boolean consumePermits) {
        final String bucketKey = getBucketKey(key);

        final Long waitMillis = this.redis.execute(SCRIPT,
                List.of(bucketKey),
                String.valueOf(this.permits),
                String.valueOf(this.regenerationMillis),
                String.valueOf(this.minDelayMillis),
                String.valueOf(this.clock.millis()),
                String.valueOf(consumePermits));

        if (waitMillis == null) {
            throw new IllegalStateException("Rate limit script returned no result for bucket " + bucketKey);
        }

        return waitMillis;
    }
}
```

- [ ] **Step 5: Run the fail-open test to verify it passes**

Run: `./mvnw -q test -Dtest=RedisLeakyBucketRateLimiterFailOpenTest`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 6: Write the Redis contract test**

```java
package com.example.registration.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.RateLimiterContractTest;
import com.example.registration.ratelimit.RegistrationAction;

@Testcontainers(disabledWithoutDocker = true)
class RedisLeakyBucketRateLimiterTest extends RateLimiterContractTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void setUpRedis() {
        this.connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        this.connectionFactory.afterPropertiesSet();

        final StringRedisTemplate redis = new StringRedisTemplate(this.connectionFactory);
        redis.afterPropertiesSet();

        final var connection = this.connectionFactory.getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @org.junit.jupiter.api.AfterEach
    void tearDownRedis() {
        this.connectionFactory.destroy();
    }

    @Override
    protected RateLimiter<String> createLimiter(final RegistrationAction action) {
        final StringRedisTemplate redis = new StringRedisTemplate(this.connectionFactory);
        redis.afterPropertiesSet();

        return new RedisLeakyBucketRateLimiter<>(
                redis, this.clock, new SimpleMeterRegistry(), action) {

            @Override
            protected String getBucketKey(final String key) {
                return getKeyPrefix() + "::" + key;
            }
        };
    }

    @Test
    void bucketKeysExpireOnceRegenerated() {
        final RateLimiter<String> limiter = createLimiter(RegistrationAction.REQUEST_SMS);
        final String bucketKey = RegistrationAction.REQUEST_SMS.getKeyPrefix() + "::" + KEY;

        assertDoesNotThrow(() -> limiter.checkRateLimit(KEY));

        assertTrue(redisHasKey(bucketKey), "a consumed bucket must be recorded in Redis");

        this.clock.advance(RegistrationAction.REQUEST_SMS.getPermitRegenerationPeriod().plusSeconds(1));

        assertFalse(redisHasKey(bucketKey),
                "a fully regenerated bucket must expire rather than accumulate");
    }

    private boolean redisHasKey(final String key) {
        final var connection = this.connectionFactory.getConnection();
        try {
            return connection.keyCommands().exists(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) > 0;
        } finally {
            connection.close();
        }
    }
}
```

- [ ] **Step 7: Run the Redis tests**

Run: `./mvnw -q test -Dtest=RedisLeakyBucketRateLimiterTest`
Expected: PASS — 9 tests (8 inherited contract tests plus `bucketKeysExpireOnceRegenerated`). If Docker is unavailable the class is skipped and Maven reports "no tests" rather than a failure; confirm by looking for `Tests run` in the output.

- [ ] **Step 8: Run the whole suite**

Run: `./mvnw -q test`
Expected: PASS, with `RedisLeakyBucketRateLimiterTest` either passing or skipped.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/validate-rate-limit.lua src/main/java/com/example/registration/ratelimit/redis src/test/java/com/example/registration/ratelimit/redis
git commit -m "feat(ratelimit): add Redis leaky bucket store driven by an atomic Lua script"
```

---

### Task 4: Bean wiring

**Files:**
- Create: `src/main/java/com/example/registration/ratelimit/PhoneNumberInMemoryRateLimiter.java`
- Create: `src/main/java/com/example/registration/ratelimit/InMemoryRateLimiterConfiguration.java`
- Create: `src/main/java/com/example/registration/ratelimit/redis/PhoneNumberRedisRateLimiter.java`
- Create: `src/main/java/com/example/registration/ratelimit/redis/RedisRateLimiterConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/registration/ratelimit/RateLimiterWiringTest.java`

**Interfaces:**
- Consumes: both stores from Tasks 2 and 3.
- Produces: exactly two beans of type `RateLimiter<Phonenumber.PhoneNumber>`, named `requestSmsRateLimiter` and `codeCheckRateLimiter`. Task 5 injects these by name.

- [ ] **Step 1: Write the failing wiring test**

```java
package com.example.registration.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.registration.ratelimit.redis.RedisLeakyBucketRateLimiter;
import com.example.registration.ratelimit.redis.RedisRateLimiterConfiguration;

class RateLimiterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> Clock.systemUTC());

    @Test
    void inMemoryIsUsedWhenNoStoreIsConfigured() {
        this.contextRunner
                .withUserConfiguration(InMemoryRateLimiterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class))
                            .isInstanceOf(InMemoryLeakyBucketRateLimiter.class);
                });
    }

    @Test
    void oneBeanIsRegisteredPerAction() {
        this.contextRunner
                .withUserConfiguration(InMemoryRateLimiterConfiguration.class)
                .run(context -> assertThat(context)
                        .hasBean("requestSmsRateLimiter")
                        .hasBean("codeCheckRateLimiter"));
    }

    @Test
    void redisIsNotRegisteredWhenNoStoreIsConfigured() {
        this.contextRunner
                .withUserConfiguration(RedisRateLimiterConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(RateLimiter.class));
    }

    @Test
    void redisIsUsedWhenConfigured() {
        this.contextRunner
                .withUserConfiguration(RedisRateLimiterConfiguration.class)
                .withPropertyValues("rate-limit.store=redis")
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class))
                            .isInstanceOf(RedisLeakyBucketRateLimiter.class);
                });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=RateLimiterWiringTest`
Expected: compilation failure — `cannot find symbol: class InMemoryRateLimiterConfiguration`.

- [ ] **Step 3: Write `PhoneNumberInMemoryRateLimiter.java`**

```java
package com.example.registration.ratelimit;

import java.time.Clock;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

/**
 * An in-memory limiter bucketed by phone number, so a caller cannot reset their allowance by creating a new session.
 */
class PhoneNumberInMemoryRateLimiter extends InMemoryLeakyBucketRateLimiter<Phonenumber.PhoneNumber> {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    PhoneNumberInMemoryRateLimiter(final Clock clock, final RegistrationAction action) {
        super(clock, action);
    }

    @Override
    protected String getBucketKey(final Phonenumber.PhoneNumber key) {
        return getKeyPrefix() + "::" + PHONE_UTIL.format(key, PhoneNumberUtil.PhoneNumberFormat.E164);
    }
}
```

- [ ] **Step 4: Write `InMemoryRateLimiterConfiguration.java`**

```java
package com.example.registration.ratelimit;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.i18n.phonenumbers.Phonenumber;

/**
 * Registers the in-memory rate limiters. This is the default; set {@code rate-limit.store=redis} to use Redis.
 */
@Configuration
@ConditionalOnProperty(prefix = "rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiterConfiguration {

    @Bean
    public RateLimiter<Phonenumber.PhoneNumber> requestSmsRateLimiter(final Clock clock) {
        return new PhoneNumberInMemoryRateLimiter(clock, RegistrationAction.REQUEST_SMS);
    }

    @Bean
    public RateLimiter<Phonenumber.PhoneNumber> codeCheckRateLimiter(final Clock clock) {
        return new PhoneNumberInMemoryRateLimiter(clock, RegistrationAction.CODE_CHECK);
    }
}
```

- [ ] **Step 5: Write `PhoneNumberRedisRateLimiter.java`**

```java
package com.example.registration.ratelimit.redis;

import java.time.Clock;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import io.micrometer.core.instrument.MeterRegistry;

import com.example.registration.ratelimit.RegistrationAction;

/**
 * A Redis limiter bucketed by phone number, so a caller cannot reset their allowance by creating a new session.
 */
class PhoneNumberRedisRateLimiter extends RedisLeakyBucketRateLimiter<Phonenumber.PhoneNumber> {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    PhoneNumberRedisRateLimiter(
            final StringRedisTemplate redis,
            final Clock clock,
            final MeterRegistry meterRegistry,
            final RegistrationAction action) {

        super(redis, clock, meterRegistry, action);
    }

    @Override
    protected String getBucketKey(final Phonenumber.PhoneNumber key) {
        return getKeyPrefix() + "::" + PHONE_UTIL.format(key, PhoneNumberUtil.PhoneNumberFormat.E164);
    }
}
```

Add the missing import to the top of that file — it is used in the constructor signature but not imported above:

```java
import org.springframework.data.redis.core.StringRedisTemplate;
```

The final import block for `PhoneNumberRedisRateLimiter.java` is:

```java
package com.example.registration.ratelimit.redis;

import java.time.Clock;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import io.micrometer.core.instrument.MeterRegistry;

import com.example.registration.ratelimit.RegistrationAction;

/**
 * A Redis limiter bucketed by phone number, so a caller cannot reset their allowance by creating a new session.
 */
class PhoneNumberRedisRateLimiter extends RedisLeakyBucketRateLimiter<Phonenumber.PhoneNumber> {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    PhoneNumberRedisRateLimiter(
            final StringRedisTemplate redis,
            final Clock clock,
            final MeterRegistry meterRegistry,
            final RegistrationAction action) {

        super(redis, clock, meterRegistry, action);
    }

    @Override
    protected String getBucketKey(final Phonenumber.PhoneNumber key) {
        return getKeyPrefix() + "::" + PHONE_UTIL.format(key, PhoneNumberUtil.PhoneNumberFormat.E164);
    }
}
```

- [ ] **Step 6: Write `RedisRateLimiterConfiguration.java`**

```java
package com.example.registration.ratelimit.redis;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.google.i18n.phonenumbers.Phonenumber;

import io.micrometer.core.instrument.MeterRegistry;

import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.RegistrationAction;

/**
 * Registers the Redis-backed rate limiters, so limits hold across every instance of the service.
 */
@Configuration
@ConditionalOnProperty(prefix = "rate-limit.store", havingValue = "redis")
public class RedisRateLimiterConfiguration {

    @Bean
    public RateLimiter<Phonenumber.PhoneNumber> requestSmsRateLimiter(
            final StringRedisTemplate redis, final Clock clock, final MeterRegistry meterRegistry) {

        return new PhoneNumberRedisRateLimiter(redis, clock, meterRegistry, RegistrationAction.REQUEST_SMS);
    }

    @Bean
    public RateLimiter<Phonenumber.PhoneNumber> codeCheckRateLimiter(
            final StringRedisTemplate redis, final Clock clock, final MeterRegistry meterRegistry) {

        return new PhoneNumberRedisRateLimiter(redis, clock, meterRegistry, RegistrationAction.CODE_CHECK);
    }
}
```

- [ ] **Step 7: Set the default store in `application.yml`**

Create `src/main/resources/application.yml` if it does not exist, or add the block to the existing file:

```yaml
spring:
  application:
    name: registration-service

rate-limit:
  store: memory
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=RateLimiterWiringTest`
Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/registration/ratelimit src/main/resources/application.yml src/test/java/com/example/registration/ratelimit/RateLimiterWiringTest.java
git commit -m "feat(ratelimit): register one limiter bean per action, in-memory by default"
```

---

### Task 5: Wire the limits into the registration response

**Files:**
- Modify: `src/main/java/com/example/registration/service/RegistrationService.java`
- Test: `src/test/java/com/example/registration/service/RegistrationServiceRateLimitTest.java`

**Interfaces:**
- Consumes: `RateLimiter<Phonenumber.PhoneNumber>` beans `requestSmsRateLimiter` and `codeCheckRateLimiter` from Task 4; `RegistrationSession` proto with field `phone_nummber`.
- Produces: `buildRegistraionSessionResponse(RegistrationSession)` now populates `mayRequestSms`, `nextSmsSeconds`, `mayCodeCheck`, `nextCodeCheckSeconds` on `RegistraionSessionMetadata`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.registration.grpc.RegistraionSessionMetadata;
import com.example.registration.ratelimit.InMemoryLeakyBucketRateLimiter;
import com.example.registration.ratelimit.MutableClock;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.RegistrationAction;
import com.example.registration.session.RegistrationSession;
import com.example.registration.session.SessionRepository;
import com.google.i18n.phonenumbers.Phonenumber;

class RegistrationServiceRateLimitTest {

    private static final String NUMBER = "+15551230123";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock;
    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        this.clock = new MutableClock(NOW);

        final RateLimiter<Phonenumber.PhoneNumber> requestSms =
                new InMemoryLeakyBucketRateLimiter<>(this.clock, RegistrationAction.REQUEST_SMS);
        final RateLimiter<Phonenumber.PhoneNumber> codeCheck =
                new InMemoryLeakyBucketRateLimiter<>(this.clock, RegistrationAction.CODE_CHECK);

        // The service and both limiters must share one clock, or "now" means different things to each of them.
        this.registrationService = new RegistrationService(
                mock(SessionRepository.class), this.clock, requestSms, codeCheck);
    }

    private RegistraionSessionMetadata response() {
        final RegistrationSession session = RegistrationSession.newBuilder()
                .setPhoneNummber(NUMBER)
                .build();

        return this.registrationService.buildRegistraionSessionResponse(session)
                .getSessionMeta();
    }

    @Test
    void aFreshNumberMayDoEverything() {
        final RegistraionSessionMetadata meta = response();

        assertTrue(meta.getMayRequestSms());
        assertEquals(0L, meta.getNextSmsSeconds());
        assertTrue(meta.getMayCodeCheck());
        assertEquals(0L, meta.getNextCodeCheckSeconds());
    }

    @Test
    void buildingTheResponseDoesNotConsumePermits() {
        final int permits = RegistrationAction.REQUEST_SMS.getPermits();

        for (int i = 0; i < permits + 5; i++) {
            assertTrue(response().getMayRequestSms(),
                    "reading the limit must never consume it");
        }
    }

    @Test
    void anExhaustedNumberIsToldWhenItMayTryAgain() {
        final RegistrationSession session = RegistrationSession.newBuilder()
                .setPhoneNummber(NUMBER)
                .build();

        final RateLimiter<Phonenumber.PhoneNumber> limiter =
                new InMemoryLeakyBucketRateLimiter<>(this.clock, RegistrationAction.REQUEST_SMS);
        final Phonenumber.PhoneNumber number =
                com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance().parse(NUMBER, null);

        for (int i = 0; i < RegistrationAction.REQUEST_SMS.getPermits(); i++) {
            this.registrationService.checkRequestSmsRateLimit(number);
            this.clock.advance(RegistrationAction.REQUEST_SMS.getMinDelay());
        }

        final RegistraionSessionMetadata meta = response();

        assertFalse(meta.getMayRequestSms(), "an exhausted number must be told to wait");
        assertTrue(meta.getNextSmsSeconds() > 0, "an exhausted number must be told how long to wait");
        assertTrue(meta.getNextSmsSeconds() <= 300,
                "the wait must not exceed one regeneration period, got " + meta.getNextSmsSeconds());

        // The code-check bucket is a separate namespace and must be untouched.
        assertTrue(meta.getMayCodeCheck(), "the SMS limit must not affect code checks");
    }

    @Test
    void theWaitIsClampedToZero() {
        final RegistrationSession session = RegistrationSession.newBuilder()
                .setPhoneNummber(NUMBER)
                .build();

        // A limiter reporting a time in the past must produce a permitted action and a non-negative wait.
        final RateLimiter<Phonenumber.PhoneNumber> pastLimiter = mock(RateLimiter.class);
        when(pastLimiter.getTimeOfNextAction(any()))
                .thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(5))));

        final RegistrationService service = new RegistrationService(
                mock(SessionRepository.class), Clock.system(ZoneOffset.UTC),
                pastLimiter, pastLimiter);

        final RegistraionSessionMetadata meta =
                service.buildRegistraionSessionResponse(session).getSessionMeta();

        assertTrue(meta.getMayRequestSms());
        assertEquals(0L, meta.getNextSmsSeconds(), "a negative wait would overflow the uint64 field");
    }
}
```

The test calls `registrationService.checkRequestSmsRateLimit(number)`, so that method must exist on `RegistrationService` as the consuming counterpart to the non-mutating read. It is added in Step 3.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=RegistrationServiceRateLimitTest`
Expected: compilation failure — no constructor of `RegistrationService` accepting two rate limiters.

- [ ] **Step 3: Rewrite `RegistrationService.java`**

```java
package com.example.registration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.registration.grpc.CreateRegistraionSessionResponse;
import com.example.registration.grpc.RegistraionSessionMetadata;
import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.session.RegistrationSession;
import com.example.registration.session.SessionMetadata;
import com.example.registration.session.SessionRepository;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

/**
 * RegistrationService
 */
@Service
public class RegistrationService {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();
    private static final Duration SESSION_TTL_AFTER_LAST_ACTION = Duration.ofMinutes(10);

    private final SessionRepository bigTableRepository;
    private final Clock clock;
    private final RateLimiter<Phonenumber.PhoneNumber> requestSmsRateLimiter;
    private final RateLimiter<Phonenumber.PhoneNumber> codeCheckRateLimiter;

    public RegistrationService(
            final SessionRepository bigTableRepository,
            final Clock clock,
            @Qualifier("requestSmsRateLimiter")
            final RateLimiter<Phonenumber.PhoneNumber> requestSmsRateLimiter,
            @Qualifier("codeCheckRateLimiter")
            final RateLimiter<Phonenumber.PhoneNumber> codeCheckRateLimiter) {

        this.bigTableRepository = bigTableRepository;
        this.clock = clock;
        this.requestSmsRateLimiter = requestSmsRateLimiter;
        this.codeCheckRateLimiter = codeCheckRateLimiter;
    }

    public RegistrationSession createRegistrationSession(
            final Phonenumber.PhoneNumber e164, final SessionMetadata sessionMetadata) {

        return this.bigTableRepository.createSession(
                e164, sessionMetadata, this.clock.instant().plus(SESSION_TTL_AFTER_LAST_ACTION));
    }

    /**
     * Consumes one SMS permit for the given number.
     *
     * @throws RateLimitExceededException if the number must wait before requesting another code
     */
    public void checkRequestSmsRateLimit(final Phonenumber.PhoneNumber e164)
            throws RateLimitExceededException {

        this.requestSmsRateLimiter.checkRateLimit(e164);
    }

    /**
     * Consumes one code-check permit for the given number.
     *
     * @throws RateLimitExceededException if the number must wait before submitting another code
     */
    public void checkCodeCheckRateLimit(final Phonenumber.PhoneNumber e164)
            throws RateLimitExceededException {

        this.codeCheckRateLimiter.checkRateLimit(e164);
    }

    public CreateRegistraionSessionResponse buildRegistraionSessionResponse(
            final RegistrationSession registrationSession) {

        final Phonenumber.PhoneNumber e164 = toPhoneNumber(registrationSession.getPhoneNummber());
        final ActionState requestSms = actionState(this.requestSmsRateLimiter, e164);
        final ActionState codeCheck = actionState(this.codeCheckRateLimiter, e164);

        return CreateRegistraionSessionResponse.newBuilder()
                .setSessionMeta(RegistraionSessionMetadata.newBuilder()
                        .setSessionId(registrationSession.getId())
                        .setE164(e164.getCountryCode() == 0 ? 0 : Long.parseUnsignedLong(
                                registrationSession.getPhoneNummber().replaceAll("\\D", "")))
                        .setMayRequestSms(requestSms.allowed())
                        .setNextSmsSeconds(requestSms.retryAfterSeconds())
                        .setMayCodeCheck(codeCheck.allowed())
                        .setNextCodeCheckSeconds(codeCheck.retryAfterSeconds())
                        .build())
                .build();
    }

    private Phonenumber.PhoneNumber toPhoneNumber(final String phoneNumber) {
        return PHONE_UTIL.parse(phoneNumber, null);
    }

    /**
     * Reads what an action's limiter currently permits. This does not consume anything, so it is safe to call on
     * every response.
     */
    private ActionState actionState(
            final RateLimiter<Phonenumber.PhoneNumber> limiter, final Phonenumber.PhoneNumber e164) {

        final Instant now = this.clock.instant();

        return limiter.getTimeOfNextAction(e164)
                .map(next -> new ActionState(
                        !next.isAfter(now),
                        Math.max(0L, Duration.between(now, next).toSeconds())))
                .orElse(new ActionState(true, 0L));
    }

    private record ActionState(boolean allowed, long retryAfterSeconds) {
    }
}
```

If `Long.parseUnsignedLong` rejects a number longer than 19 digits in your test fixtures, replace the `setE164` line with `.setE164(parseE164Digits(registrationSession.getPhoneNummber()))` and add:

```java
    private static long parseE164Digits(final String phoneNumber) {
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < phoneNumber.length(); i++) {
            if (Character.isDigit(phoneNumber.charAt(i))) {
                digits.append(phoneNumber.charAt(i));
            }
        }
        return Long.parseUnsignedLong(digits.toString());
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./m