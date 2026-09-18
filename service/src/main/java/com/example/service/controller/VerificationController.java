package com.example.service.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.service.entities.CreateVerificationSessionRequest;
import com.example.service.registration.VerificationSession;
import com.example.service.telephony.CarrierData;
import com.example.service.telephony.CarrierDataException;
import com.example.service.telephony.CarrierDataProvider;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.lang.foreign.Linker.Option;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;

@RestController
@Slf4j
@RequestMapping("/v1/verification")
public class VerificationController {

    private final CarrierDataProvider carrierDataProvider;
    private final Clock clock;

    VerificationController(@Qualifier("HlrLookupCarrierDataProvider") final CarrierDataProvider carrierDataProvider,
    final Clock clock) {
        this.carrierDataProvider = carrierDataProvider;
        this.clock = clock;
    }

    @Operation(summary = "Creates a new verification session for a specific phone number", description = """
            Initiates a session to be able to verify the phone number for account registration. Check the response and
            submit requested information at PATCH /session/{sessionId}
            """)
    @PostMapping(
            value = "/session",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public String createSession(@NotNull @Valid @RequestBody final CreateVerificationSessionRequest request)
            throws NumberParseException {

        final Phonenumber.PhoneNumber phoneNumber =
                PhoneNumberUtil.getInstance().parse(request.number(), null);

        Optional<CarrierData> maybeCarrierData;

        try {
            maybeCarrierData = carrierDataProvider.lookupCarrierData(phoneNumber, Duration.ZERO);

        } catch (IOException | CarrierDataException ex) {
            log.error("Failed to retrieve carrier data: " ,ex);
            maybeCarrierData = Optional.empty();
        }


        VerificationSession verificationSession = new VerificationSession("", 
        maybeCarrierData.orElse(null),
         new ArrayList<>(), 
         Collections.emptyList(),
          clock.millis(), 
          clock.millis(),
           0)

        return "";
    }
}
