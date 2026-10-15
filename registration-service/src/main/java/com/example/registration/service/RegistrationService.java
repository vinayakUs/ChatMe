package com.example.registration.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.registration.grpc.CreateRegistraionSessionResponse;
import com.example.registration.grpc.RegistraionSessionMetadata;
import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.redis.PhoneNumberAndCollationKey;
import com.example.registration.session.RegistrationSession;
import com.example.registration.session.SessionMetadata;
import com.example.registration.session.SessionRepository;
import com.google.i18n.phonenumbers.Phonenumber;

/**
 * RegistrationService
 */
@Service
public class RegistrationService {

    private final SessionRepository bigTableRepository;
    private final Clock clock;
    private static Duration SESSION_TTL_AFTER_LAST_ACTION = Duration.ofMinutes(10);

    private final RateLimiter<PhoneNumberAndCollationKey> sessionCreationRateLimiter;

    public RegistrationService(SessionRepository bigTableRepository, Clock clock,
            @Qualifier("session-creation") RateLimiter<PhoneNumberAndCollationKey> sessionCreationRateLimiter) {
        this.bigTableRepository = bigTableRepository;
        this.clock = clock;
        this.sessionCreationRateLimiter = sessionCreationRateLimiter;
    }

    public RegistrationSession createRegistrationSession(
            final Phonenumber.PhoneNumber e164,
            final String rateLimitCollationKey,
            SessionMetadata sessionMetadata) throws RateLimitExceededException {

        sessionCreationRateLimiter.checkRateLimit(new PhoneNumberAndCollationKey(e164, rateLimitCollationKey));

        return bigTableRepository.createSession(
                e164, sessionMetadata, clock.instant().plus(SESSION_TTL_AFTER_LAST_ACTION));
    }

    public CreateRegistraionSessionResponse buildRegistraionSessionResponse(RegistrationSession registrationSession) {
        return CreateRegistraionSessionResponse.newBuilder()
                .setSessionMeta(RegistraionSessionMetadata.newBuilder().build())
                .build();
    }
}
