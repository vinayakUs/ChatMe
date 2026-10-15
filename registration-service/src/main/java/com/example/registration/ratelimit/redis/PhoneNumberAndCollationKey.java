package com.example.registration.ratelimit.redis;

import com.google.i18n.phonenumbers.Phonenumber;

/**
 * PhoneNumberAndCollationKey
 */
public record PhoneNumberAndCollationKey(
    Phonenumber.PhoneNumber phoneNumber,
    String collationKey
) {
}