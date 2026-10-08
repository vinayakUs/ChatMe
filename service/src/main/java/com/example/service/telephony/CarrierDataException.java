package com.example.service.telephony;

/**
 * Indicates the req for fetching carrier data has be permentenly failed 
 * CarrierDataException
 */
public class CarrierDataException extends Exception {
    
    public CarrierDataException(final String message){
        super(message);
    }
    
}
