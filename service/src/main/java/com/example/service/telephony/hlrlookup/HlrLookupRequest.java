package com.example.service.telephony.hlrlookup;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** 
 * Request DTO class for HLR Api Lookup.
 * HlrLookupRequest
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HlrLookupRequest(
        @NotNull String apiKey,
        @NotNull String apiSecret,
        @NotEmpty List<TelephoneNumberRequest> requests) {}
