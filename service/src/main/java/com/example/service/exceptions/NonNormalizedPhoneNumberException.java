package com.example.service.exceptions;

/**
 * NonNormalizedPhoneNumberException
 */
public class NonNormalizedPhoneNumberException extends Exception {

    private final String orignalNumber;
    private final String normailzedNumber;

    public NonNormalizedPhoneNumberException(String orignalNumber, String normailzedNumber) {
        this.normailzedNumber = normailzedNumber;
        this.orignalNumber = orignalNumber;
    }

    public String getOrignalNumber() {
        return orignalNumber;
    }

    public String getNormailzedNumber() {
        return normailzedNumber;
    }

}
