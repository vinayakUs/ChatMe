package com.example.service.entities;

import java.util.Base64;

import org.jspecify.annotations.Nullable;

/**
 * RegistrationServiceSession
 */
public record RegistrationServiceSession(
        byte[] id,
        String number,
        boolean verified,
        @Nullable Long nextSms,
        @Nullable Long nextVoiceCall,
        @Nullable Long nextVerificationAttempt,
        long expiration) {

    public String encodeSessionId() {
        return encodeSessionId(id);
    }

    public static String encodeSessionId(byte[] id) {
        return Base64.getUrlEncoder().encodeToString(id);
    }
}
