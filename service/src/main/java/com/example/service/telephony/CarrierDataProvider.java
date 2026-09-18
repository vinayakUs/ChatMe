package com.example.service.telephony;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import com.google.i18n.phonenumbers.Phonenumber;

/**
 * CarrierDataProvider
 */
public interface CarrierDataProvider {

    /**
     * Retrives carrier data from given phone
     * @param phoneNumber phonenumber for which to retrive line and home network information. 
     * @param maxCachedDuration max age of cached response to retrive, provider must attempt to retrive fresh data 
     * if cached data is older that the duration specified.
     * @return line type and home network information for the given phone number if available or empty if the 
     * provider could not find the information. 
     * @throws IOException if provider couldnt be reacted due to network issues
     * @throws CarrierDataException if the request failed and should not be retried without modification
     */
    Optional<CarrierData> lookupCarrierData(Phonenumber.PhoneNumber phoneNumber,Duration maxCachedDuration) throws IOException,CarrierDataException;
    
}