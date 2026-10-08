package com.example.service.telephony.hlrlookup;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import io.github.resilience4j.core.lang.Nullable;
import jakarta.validation.constraints.NotBlank;

/**
 * TelephoneNumberRequest
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TelephoneNumberRequest(
                @NotBlank @JsonProperty("telephone_number") String telephoneNumber,
                @Nullable @JsonProperty("cache_days_private") Integer cacheDaysPrivate,
                @Nullable @JsonProperty("cache_days_global") Integer cacheDaysGlobal,
                @NotBlank @JsonProperty("save_to_cache") String saveToGlobalCache) {

        static TelephoneNumberRequest forPhoneNumber(final Phonenumber.PhoneNumber phoneNumber,
                        final Duration maxCacheDuration) {

                return new TelephoneNumberRequest(
                                StringUtils.stripStart(PhoneNumberUtil.getInstance().format(phoneNumber,
                                                PhoneNumberUtil.PhoneNumberFormat.E164), "+"),
                                (int) maxCacheDuration.toDays(),
                                (int) maxCacheDuration.toDays(), "NO");

        }
}