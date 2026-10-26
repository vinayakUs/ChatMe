package com.example.registration.ratelimit.redis.fixeddelay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.session.RegistrationSession;

/**
 * FixDelayRateLimiter controls in memory rate limiter
 * control pace and absolute no of times action can be taken in contenxt of
 * registration Session
 * 
 */
abstract class FixedDelayRegistrationSessionRateLimiter implements RateLimiter<RegistrationSession> {

    private final Clock clock;
    private final List<Duration> delays;

    public FixedDelayRegistrationSessionRateLimiter(
        Clock clock,
        List<Duration> delays
    ) {
        this.clock = clock;
        this.delays = delays;
    }

    @Override
    public Optional<Instant> getNextActionTime(RegistrationSession key) {

        Optional<Instant> maybeLastAttempTime = getLastAttemptTime(key);
        Optional<Instant> maybeNextAttempTime;
        int attempts = getAttemptsCount(key);

        if (attempts == 0) {
            maybeNextAttempTime = Optional.of(Instant.ofEpochMilli(key.getCreatedEpochMillis()));
        } else if (attempts <= delays.size()) {
            maybeNextAttempTime = Optional.of(maybeLastAttempTime.orElseThrow(
                    () -> new IllegalStateException("Last attempt must be present if attempt count is non-zero"))
                    .plus(delays.get(attempts - 1)));
        } else {
            maybeNextAttempTime = Optional.empty();
        }
        return maybeNextAttempTime;
    }

    abstract int getAttemptsCount(RegistrationSession key);

    abstract Optional<Instant> getLastAttemptTime(RegistrationSession key);

    @Override
    public void checkRateLimit(RegistrationSession key) throws RateLimitExceededException {
        Optional<Instant> maybeTimeOfNextAction = getNextActionTime(key);

        if (maybeTimeOfNextAction.isPresent()) {
            final Instant currentTime = clock.instant();
            final Instant nextActionTime = maybeTimeOfNextAction.get();

            if (currentTime.isBefore(nextActionTime)) {
                throw new RateLimitExceededException(Duration.between(currentTime, nextActionTime));
            }
        } else {
            throw new RateLimitExceededException(null);
        }
    }

}
