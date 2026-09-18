package com.example.service.telephony.hlrlookup;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import io.github.resilience4j.core.lang.Nullable;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * TelephoneNumberRequest
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TelephoneNumberRequest(
                @NotBlank String telephoneNumber,
                @Nullable Integer cacheDaysPrivate,
                @Nullable Integer cacheDaysGlobal,
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