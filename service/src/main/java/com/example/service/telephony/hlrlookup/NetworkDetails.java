package com.example.service.telephony.hlrlookup;

/**
 * NetworkDetails
 */
record NetworkDetails(
        String name, String mccmnc, String countryName, String countryIso3, String area, String countryPrefix) {}
