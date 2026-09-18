package com.example.service.telephony.hlrlookup;


import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record HlrLookupResult(
        String error,
        float creditsSpent,
        String originalNetwork,
        NetworkDetails originalNetworkDetails,
        String currentNetwork,
        NetworkDetails currentNetworkDetails,
        String telephoneNumberType,
        String isPorted,
        String disposableNumber) {}
