package com.example.registration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.checkerframework.checker.units.qual.t;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.registration.grpc.CreateRegistraionSessionResponse;
import com.example.registration.grpc.RegistraionSessionMetadata;
import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.ratelimit.RateLimiter;
import com.example.registration.ratelimit.redis.leakybucket.PhoneNumberAndCollationKey;
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
    private final RateLimiter<RegistrationSession> sendSmsVerificationCodePerSessionRateLimiter;
    private final RateLimiter<RegistrationSession> checkVerificationCodePerSessionRateLimiter;

    /**
     * NextActionItemTimes
     */
    record NextActionItemTimes(
            Optional<Instant> nextSms,
            Optional<Instant> nextCodeCheck) {
        static NextActionItemTimes EMPTY = new NextActionItemTimes(Optional.empty(), Optional.empty());
    }

    public RegistrationService(SessionRepository bigTableRepository, Clock clock,
            @Qualifier("session-creation") RateLimiter<PhoneNumberAndCollationKey> sessionCreationRateLimiter,
            @Qualifier("send-sms-verification-code-per-session") RateLimiter<RegistrationSession> sendSmsVerificationCodePerSessionRateLimiter,
            @Qualifier("check-verification-code-per-session") RateLimiter<RegistrationSession> checkVerificationCodePerSessionRateLimiter

    ) {
        this.bigTableRepository = bigTableRepository;
        this.clock = clock;
        this.sessionCreationRateLimiter = sessionCreationRateLimiter;
        this.sendSmsVerificationCodePerSessionRateLimiter = sendSmsVerificationCodePerSessionRateLimiter;
        this.checkVerificationCodePerSessionRateLimiter = checkVerificationCodePerSessionRateLimiter;
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

        /**
         * --bytes session_id = 1;
         * --uint64 e164 = 2;
         * --bool verified = 8;
         * bool may_request_sms = 3;
         * uint64 next_sms_seconds = 4;
         * bool may_code_check = 5;
         * uint64 next_code_check_seconds = 6;
         * --uint64 expiration_seconds = 7;
         */

        RegistraionSessionMetadata.Builder sessionMetadata = RegistraionSessionMetadata.newBuilder();

        sessionMetadata.setSessionId(registrationSession.getId());
        sessionMetadata
                .setE164(Long.parseLong(StringUtils.trimLeadingCharacter(registrationSession.getPhoneNummber(), '+')));
        sessionMetadata.setVerified(StringUtils.hasText(registrationSession.getVerifiedCode()));

        sessionMetadata.setExpirationSeconds(
                Duration.between(clock.instant(), getSessionExpiration(registrationSession)).getSeconds());

        final NextActionItemTimes itemTimes = getNextActionItemsTime(registrationSession);
        final Instant currentTime = clock.instant();

        itemTimes.nextSms.ifPresent(
                nextAction -> {
                    sessionMetadata.setMayRequestSms(true);
                    sessionMetadata.setNextSmsSeconds(
                            nextAction.isBefore(currentTime) ? 0
                                    : Duration.between(currentTime, nextAction).toSeconds());
                });

        itemTimes.nextCodeCheck.ifPresent(
                nextAction -> {
                    sessionMetadata.setMayCodeCheck(true);
                    sessionMetadata.setNextCodeCheckSeconds(
                            nextAction.isBefore(currentTime) ? 0
                                    : Duration.between(currentTime, nextAction).toSeconds());
                });

        return CreateRegistraionSessionResponse.newBuilder()
                .setSessionMeta(sessionMetadata.build())
                .build();
    }

    public NextActionItemTimes getNextActionItemsTime(final RegistrationSession session) {

        if (StringUtils.hasText(session.getVerifiedCode())) {
            return NextActionItemTimes.EMPTY;
        }

        final Optional<Instant> nextSms = sendSmsVerificationCodePerSessionRateLimiter.getNextActionTime(session);

        Optional<Instant> nextCodeCheck = Optional.empty();
        // caller can check code if there's active attempt

        if (session.getRegistrationAttemptsCount() > 0) {
            Instant currentAttemptExpiration = Instant.ofEpochMilli(session
                    .getRegistrationAttempts(session.getRegistrationAttemptsCount() - 1).getExpirationEpochMillis());

            if (!clock.instant().isAfter(currentAttemptExpiration)) {
                nextCodeCheck = checkVerificationCodePerSessionRateLimiter.getNextActionTime(session);
            }

        }

        return new NextActionItemTimes(nextSms, nextCodeCheck);
    }

    Instant getSessionExpiration(RegistrationSession session) {

        if (StringUtils.hasText(session.getVerifiedCode())) {
            return Instant.ofEpochMilli(session.getLastCheckCodeAttemptEpochMillis())
                    .plus(SESSION_TTL_AFTER_LAST_ACTION);
        }

        final List<Instant> candinateExpiration = session.getRegistrationAttemptsList().stream().map(
                attempt -> {
                    return Instant.ofEpochMilli(attempt.getExpirationEpochMillis());
                }).toList();

        return candinateExpiration.stream().max(Comparator.naturalOrder())
                .orElse(Instant.ofEpochMilli(session.getExpirationEpochMillis()));
    }
}
