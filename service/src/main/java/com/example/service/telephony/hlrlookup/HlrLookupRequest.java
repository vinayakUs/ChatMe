package com.example.service.telephony.hlrlookup;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * HlrLookupRequest
 */
public record HlrLookupRequest(
        @NotNull @JsonProperty("api_key") String apiKey,
        @NotNull @JsonProperty("api_secret") String apiSecret,
        @NotEmpty @JsonProperty("requests") List<TelephoneNumberRequest> requests) {}
