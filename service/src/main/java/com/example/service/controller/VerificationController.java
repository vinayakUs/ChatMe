package com.example.service.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.service.entities.CreateVerificationSessionRequest;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/v1/verification")
public class VerificationController {

    @Operation(summary = "Creates a new verification session for a specific phone number", description = """
            Initiates a session to be able to verify the phone number for account registration. Check the response and
            submit requested information at PATCH /session/{sessionId}
            """)
    @PostMapping(value = "/session", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public String createSession(@NotNull @Valid @RequestBody final CreateVerificationSessionRequest request) throws NumberParseException {

        final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse(request.number(), null);
        
        return "";
    }

}
