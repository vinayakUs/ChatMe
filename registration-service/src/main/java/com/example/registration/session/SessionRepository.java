package com.example.registration.session;

import java.time.Instant;

import com.google.i18n.phonenumbers.Phonenumber;

/**
 * A session repository stores and retrieves data associated with registration sessions
 */
public interface SessionRepository {

    /**
     * Stores a new registration session.
     *
     * @param e164 phone no to be verified
     * @param sessionMetadata addition session meta which need to be store created from user request parameters
     * @param expiration session expiration time
     *
     * @return newly create reistration session
     */
    public RegistrationSession createSession(
            Phonenumber.PhoneNumber e164, SessionMetadata sessionMetadata, Instant expiration);
}
