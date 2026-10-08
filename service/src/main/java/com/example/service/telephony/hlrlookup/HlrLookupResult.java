package com.example.service.telephony.hlrlookup;

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
