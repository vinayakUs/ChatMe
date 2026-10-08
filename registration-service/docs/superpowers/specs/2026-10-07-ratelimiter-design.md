# Rate Limiter Design

**Date:** 2026-10-07
**Status:** Draft
**Reference:** Adapted from Signal's `org.signal.registration.ratelimit` package.

## Problem

`registration_service.proto` already exposes the fields a rate limiter must fill:

```protobuf
message RegistraionSessionMetadata {
    bytes session_id = 1;
    uint64 e164 = 2;
    bool verified = 8;
    bool may_request_sms = 3;
    uint64 next_sms_seconds = 4;
    bool may_code_check = 5;
    uint64 next_code_check_seconds = 6;
    uint64 expiration_seconds = 7;
}
```

Today nothing computes `may_request_sms`, `next_sms_seconds`, `may_code_check`, or
`next_code_check_seconds`. `RegistrationService.buildRegistraionSessionResponse` returns a
default-constructed `RegistraionSessionMetadata`, so every caller is told it may request an SMS
and every code check is permitted.

We need a rate limiter that answers two questions per action:

1. May this caller take this action right now?
2. If not, when may they take it next?

It must work in two deployments: a single-process in-memory store, and a Redis-backed store for
running multiple instances.

## Goals

- One interface, two interchangeable stores, identical observable behaviour.
- Bucket keys derived from the E.164 phone number and the action, so creating a fresh session does
  not reset a caller's allowance. This is the OTP-abuse case: without it, an attacker requests a
  new session and re-spams a number without limit.
- Non-mutating queries, so building a response never consumes allowance.
- Limits declared in one place, not scattered across configuration.

## Non-goals

- Rate limiting session *creation*. Signal limits it; this spec does not. It is a natural third
  action once the mechanism is in place.
- Per-session limits in addition to per-number. Signal enforces both. Per-number alone satisfies
  the stated need and halves the storage; per-session can be added later without an interface change.
- Token bucket, sliding-window log, or any algorithm other than leaky bucket.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Algorithm | Leaky bucket | Produces a precise "next allowed" time, which is the whole point of `next_sms_seconds`. A fixed-window counter can only report the window boundary. |
| Store | In-memory + Redis, same behaviour | In-memory for single-instance dev and tests; Redis for multi-instance production. |
| Shape | One bean per action | Signal's shape. Keeps each action's limits and key prefix isolated in its own class. |
| Bucket key | `<action>::<E164>` | Survives session recreation. Action prefix keeps both actions in separate buckets under one Redis. |
| Limits | On the `RegistrationAction` enum | One place to read all limits; no configuration plumbing. |
| Redis outage | Fail open, increment a counter | Registration keeps working when Redis is unavailable. The counter makes the gap visible. |

### Deviations from Signal

- **Wiring.** Signal uses Micronaut's `@Requires(bean = StatefulRedisConnection.class)`. The Spring
  equivalent (`@ConditionalOnBean`) is order-dependent when used inside a user `@Configuration`,
  because auto-configurations are processed last. We use
  `@ConditionalOnProperty(prefix = "rate-limit.store", havingValue = "redis")` for the Redis
  configuration and `@ConditionalOnMissingBean(RateLimiter.class)` for the in-memory one. One
  explicit property beats a condition that silently does not fire.
- **Client.** Signal drives Lettuce directly and hand-rolls `EVALSHA` with a `NOSCRIPT` retry.
  We use Spring's `StringRedisTemplate` with a `DefaultRedisScript`, which handles `NOSCRIPT`
  itself.

## Architecture

```
com.example.registration.ratelimit
├── RateLimiter.java                     interface
├── RateLimitExceededException.java
├── RegistrationAction.java              action identity + limits
├── InMemoryLeakyBucketRateLimiter.java  store #1
└── redis
    ├── RedisLeakyBucketRateLimiter.java store #2
    └── validate-rate-limit.lua          shared algorithm, atomic on Redis
```

### The interface

```java
public interface RateLimiter<K> {

    /**
     * Returns the next time at which this rate limiter will permit the rate-limited action.
     * If the returned time is before or equal to the current time, the caller may take the
     * action immediately.
     *
     * <p>Implementations MUST NOT change rate limiter state. Callers must not be penalised
     * for asking.
     */
    Optional<Instant> getTimeOfNextAction(K key);

    /**
     * Checks whether the rate-limited action may be taken, consuming one permit if so.
     *
     * @throws RateLimitExceededException if the caller must wait
     */
    void checkRateLimit(K key) throws RateLimitExceededException;
}
```

Two methods rather than one `tryAcquire` returning a decision. `RegistraionSessionMetadata` needs
`next_sms_seconds` *before* the caller decides to send, and then `may_request_sms` must reflect a
permit actually consumed. A single combined operation cannot serve both without either
double-counting or leaving `may_*` unconsumed. Signal reached the same conclusion.

`Optional<Instant>` rather than `Optional<Duration>` so that callers compare against a real clock
reading rather than assuming "now" is whenever they happened to call. An empty Optional means no
amount of waiting will help — the caller needs to take some other action. The current
implementations never return empty, but the contract reserves it.

### RegistrationAction

```java
public enum RegistrationAction {
    REQUEST_SMS("request-sms", 3, Duration.ofMinutes(5), Duration.ofSeconds(30)),
    CODE_CHECK("code-check",  5, Duration.ofMinutes(1), Duration.ofSeconds(5));

    private final String keyPrefix;
    private final int permits;
    private final Duration permitRegenerationPeriod;
    private final Duration minDelay;

    String getKeyPrefix()          { return keyPrefix; }
    int getPermits()               { return permits; }
    Duration getPermitRegenerationPeriod() { return permitRegenerationPeriod; }
    Duration getMinDelay()         { return minDelay; }
}
```

`minDelay` is the cooldown that stops a caller from re-sending the same verification code
instantly even while permits remain. This is why the leaky bucket beat a plain counter for this
problem.

### One bean per action

```java
@Named("request-sms") RateLimiter<PhoneNumber> requestSmsRateLimiter;
@Named("code-check")  RateLimiter<PhoneNumber> codeCheckRateLimiter;
```

Callers select with a switch, as Signal does:

```java
private RateLimiter<PhoneNumber> limiterFor(RegistrationAction action) {
    return switch (action) {
        case REQUEST_SMS -> requestSmsRateLimiter;
        case CODE_CHECK  -> codeCheckRateLimiter;
    };
}
```

Each action's bucket key is `"<action>::<E164>"`, e.g. `request-sms::+15551230123`. The phone
number is formatted E.164 by `PhoneNumberUtil` so that `+15551230123` and a differently punctuated
`1 555 123 0123` share a bucket.

### RateLimitExceededException

Carries a nullable `retryAfterDuration` and, optionally, the `RegistrationSession` in play, so a
caller catching the exception can return useful state rather than a bare failure.

`retryAfterDuration` is null when the limiter could not determine a wait — currently only the
fail-closed Redis path, which this project does not use. The field stays nullable because the
in-memory limiter can produce it and the exception should not have to know that.

### The shared algorithm

Both stores implement identical arithmetic over a bucket of
`{permitsRemaining, lastUpdateTimeMillis}`:

1. On first sight: `permitsRemaining = bucketSize`, `lastUpdateTime = now`, cooldown 0.
2. `available = min(bucketSize, permitsRemaining + elapsed / regenerationPeriodMillis)`.
3. If `available >= 1` and cooldown elapsed:
   - consume mode: decrement, set `lastUpdateTime = now`, persist.
   - return 0 (allowed).
4. Otherwise return `max(ceil((1 - available) * regenerationPeriodMillis), remainingCooldown)` as
   milliseconds until allowed.

The Redis script returns that duration in milliseconds; `RedisLeakyBucketRateLimiter` converts it
to an `Instant` via `clock.instant().plusMillis(...)`. Both implementations inject `Clock`, so both
are testable with a fixed clock and neither reads the system time directly.

**Memory bound.** The Redis store self-expires: once the bucket refills, the script deletes the
key. The in-memory store has no equivalent, so `InMemoryLeakyBucketRateLimiter` runs a lazy sweep
— every N operations it evicts buckets whose permits have fully regenerated
(`now - lastUpdate >= permits * regenerationPeriod + minDelay`). This matches the Redis lifecycle so
the two stores do not diverge in observable behaviour.

**Concurrency.** The in-memory store performs `checkRateLimit` inside
`ConcurrentHashMap.compute`, so concurrent checks for the same key serialise. `getTimeOfNextAction`
reads without mutating and takes no lock; a stale read is harmless because it only ever reports a
time slightly in the past, and the subsequent `checkRateLimit` is authoritative.

### Wiring

```java
@Configuration
@ConditionalOnProperty(prefix = "rate-limit.store", havingValue = "redis")
class RedisRateLimiterConfiguration { /* two Redis-backed beans */ }

@Configuration
@ConditionalOnMissingBean(RateLimiter.class)
class InMemoryRateLimiterConfiguration { /* two in-memory beans */ }
```

Default is in-memory, so tests and local development need no configuration. Setting
`rate-limit.store=redis` in `application.yml` selects Redis for every action at once.

Both configurations inject the `StringRedisTemplate` that Spring Boot's Redis auto-configuration
already provides, and the existing `Clock` bean from `ClockConfig`. Neither store constructs its own
connection.

## Data flow

Building the response — non-mutating, safe to call for every caller:

```java
final Optional<Instant> nextSms = requestSmsRateLimiter
        .getTimeOfNextAction(phoneNumber);
final boolean mayRequestSms = nextSms.map(i -> !i.isAfter(clock.instant())).orElse(true);

final long nextSmsSeconds = nextSms
        .map(i -> Math.max(0, Duration.between(clock.instant(), i).toSeconds()))
        .orElse(0L);
```

Actually sending — consumes a permit or throws:

```java
try {
    requestSmsRateLimiter.checkRateLimit(phoneNumber);
} catch (RateLimitExceededException e) {
    // return may_request_sms=false with next_sms_seconds from
    // e.getRetryAfterDuration(), not a generic error
}
```

The catch is not an error path. A rate-limited caller is a normal outcome that the proto already has
fields for, so it maps to `may_request_sms = false` plus a positive `next_sms_seconds`, not to
`CreateRegistrationSessionError`.

## Error handling

| Situation | Behaviour |
|---|---|
| Rate limit exceeded | Throw `RateLimitExceededException` with the wait duration. Caller maps to `may_* = false`. |
| Redis unreachable | Log a warning, increment `ratelimiter.failed.open` counter, allow the action. Registration degrades rather than fails. |
| Malformed phone number | The gRPC layer already catches and returns `CreateRegistrationSessionError`. The limiter does not parse; it receives an `E164` `PhoneNumber` and formats it. |
| Lua script missing from Redis | `DefaultRedisScript` retries with `EVAL` after `NOSCRIPT`. Handled by Spring. |

## Testing

**Contract test.** One abstract class, `RateLimiterContractTest`, holds every behavioural
assertion. Both store test classes extend it and supply only a `createLimiter(...)` factory. This is
the test that earns the abstraction: it is what guarantees the in-memory and Redis implementations
are interchangeable, and it fails loudly if they ever diverge.

Cases: fresh key allows immediately; the Nth call allows and the N+1th does not; permits regenerate
over time; the cooldown blocks an immediate repeat even with permits remaining; querying does not
consume; distinct actions have distinct buckets; the reported wait matches the time at which the
call is actually permitted.

**In-memory store.** Extends the contract test. Adds: concurrent checks for one key consume exactly
`permits` permits; the sweep evicts fully-regenerated buckets and leaves live ones.

**Redis store.** Extends the contract test against a Testcontainers `redis:6.2-alpine` instance,
mirroring Signal's test. Adds: bucket keys expire once regenerated.

**Fail-open path.** A mock `StringRedisTemplate` that throws `RedisConnectionException`; assert the
action is allowed and the counter increments.

Tests use a `MutableClock` test helper (a `Clock` subclass with a settable instant) rather than a
mocked `Clock`, so time moves forward explicitly and assertions read in sequence.

The Redis tests require Docker. `@Testcontainers(disabledWithoutDocker = true)` skips them when no
Docker daemon is reachable, so the build stays green on a machine without Docker while still running
them wherever Docker is available.

## Dependencies

Add to `pom.xml`:

- `spring-boot-starter-data-redis` — Lettuce client and `StringRedisTemplate`
- `spring-boot-starter-actuator` — Micrometer, for the failed-open counter
- `com.redis:testcontainers` — Testcontainers Redis module, test scope

## Out of scope, deliberately

Session-creation limiting, per-session (as opposed to per-number) buckets, configurable limits at
runtime, and metrics beyond the failed-open counter. Each is a small follow-up that the interface
supports; none is needed to fix the reported gap.