package com.example.service.util;

import com.example.service.exceptions.ImpossiblePhoneNumberException;
import com.example.service.exceptions.NonNormalizedPhoneNumberException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

public class Util {

    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

    /**
     * Checks wether given number is valid E.164 noramalized number.
     * 
     * @param number the number to check
     * @throws ImpossiblePhoneNumberException
     * @throws NonNormalizedPhoneNumberException
     * 
     */
    public static void isNormalizedNumberRequired(final String number)
            throws ImpossiblePhoneNumberException, NonNormalizedPhoneNumberException {

        if (!PHONE_NUMBER_UTIL.isPossibleNumber(number, null)) {
            throw new ImpossiblePhoneNumberException();
        }
        try {
            PhoneNumber inputNumber = PHONE_NUMBER_UTIL.parse(number, null);

            String normailzedE164 = PHONE_NUMBER_UTIL.format(inputNumber, PhoneNumberUtil.PhoneNumberFormat.E164);

            if (!number.equals(normailzedE164)) {
                throw new NonNormalizedPhoneNumberException(number, normailzedE164);
            }

        } catch (NumberParseException e) {
            throw new ImpossiblePhoneNumberException();
        }
    }



}
