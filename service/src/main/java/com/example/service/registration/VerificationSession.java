package com.example.service.registration;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.example.service.telephony.CarrierData;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Internal Stored Session Object
 * VerificationSession
 * @param sessionId the session ID returned by Registration Service
 * @param carrierData information about the phone number's carrier if available
 * @param requestedInformation    information requested that a client send to the server
 * @param submittedInformation    information that a client has submitted and that the server has verified
 * @param createdTimestamp        when this session was created 
 * @param updatedTimestamp        when this session was updated
 * @param remoteExpirationSeconds when the remote
 *
 *
 */
public record VerificationSession(
        String sessionId,
        @Nullable CarrierData carrierData,
        List<Information> requestedInformation,
        List<Information> submittedInformation,
        long createdTimestamp,
        long updatedTimestamp,
        long remoteExpirationSeconds) {
    public enum Information {
        @JsonProperty("captcha")
        CAPTCHA
    }
}
