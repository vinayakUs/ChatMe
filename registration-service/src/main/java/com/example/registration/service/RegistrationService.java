package com.example.registration.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Service;

import com.example.registration.grpc.CreateRegistraionSessionResponse;
import com.example.registration.grpc.RegistraionSessionMetadata;
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

    public RegistrationService(SessionRepository bigTableRepository, Clock clock) {
        this.bigTableRepository = bigTableRepository;
        this.clock = clock;
    }

    public RegistrationSession createRegistrationSession(
            final Phonenumber.PhoneNumber e164, SessionMetadata sessionMetadata) {

        return bigTableRepository.createSession(
                e164, sessionMetadata, clock.instant().plus(SESSION_TTL_AFTER_LAST_ACTION));
    }

    public CreateRegistraionSessionResponse buildRegistraionSessionResponse(RegistrationSession registrationSession) {
        return CreateRegistraionSessionResponse.newBuilder()
                .setSessionMeta(RegistraionSessionMetadata.newBuilder().build())
                .build();
    }
}
