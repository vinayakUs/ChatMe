package com.example.registration.ratelimit.redis.fixeddelay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.registration.session.RegistrationSession;

@Component("check-verification-code-per-session")
public class CheckVerificationCodePerSessionRateLimiter extends FixedDelayRegistrationSessionRateLimiter {

    public CheckVerificationCodePerSessionRateLimiter(Clock clock,@Value("${rate-limits.check-verification-code.delays}") List<Duration> delays) {
        super(clock, delays);
    }

    @Override
    int getAttemptsCount(RegistrationSession session) {
        return session.getCheckCodeAttempts();
    }

    @Override
    Optional<Instant> getLastAttemptTime(RegistrationSession session) {
        return getAttemptsCount(session) > 0
                ? Optional.of(Instant.ofEpochMilli(session.getLastCheckCodeAttemptEpochMillis()))
                : Optional.empty();
    }

}
