package com.example.service.telephony.hlrlookup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.service.http.FaultTolerantHttpClient;
import com.example.service.telephony.CarrierData;
import com.example.service.telephony.CarrierDataException;
import com.example.service.telephony.CarrierDataProvider;
import com.google.i18n.phonenumbers.Phonenumber;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/// A carrier data provider that uses [HLR Lookup](https://www.hlrlookup.com/) as its data source.
/// For dev we are using testing api
/// curl -s -H "Content-Type: application/json" -X POST \
//   -d '{"api_key":"speedtest","api_secret":"speedtest",
//        "requests":[{"telephone_number":"441133910781"}]}' \
//   https://testing.hlrlookup.com/apiv2/hlr

@Service(value = "HlrLookupCarrierDataProvider")
public class HlrLookupCarrierDataProvider implements CarrierDataProvider {

    private final FaultTolerantHttpClient faultTolerantHttpClient;

    private static final URI lookupUri = URI.create("https://testing.hlrlookup.com/apiv2/hlr");
    private final ObjectMapper objectMapper;
    private final String apiKey = "speedtest";
    private final String apiSecret = "speedtest";

    public HlrLookupCarrierDataProvider(
            @Qualifier("default") final FaultTolerantHttpClient faultTolerantHttpClient, ObjectMapper objectMapper) {
        this.faultTolerantHttpClient = faultTolerantHttpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CarrierData> lookupCarrierData(Phonenumber.PhoneNumber phoneNumber, Duration maxCachedDuration)
            throws IOException, CarrierDataException {

        final HlrLookupResponse response;

        try {

            final String requestJson = objectMapper.writeValueAsString(new HlrLookupRequest(
                    apiKey, apiSecret, List.of(TelephoneNumberRequest.forPhoneNumber(phoneNumber, maxCachedDuration))));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(lookupUri)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .header("Content-Type", "application/json")
                    .build();

            final HttpResponse<String> httpResponse =
                    faultTolerantHttpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {

                try {
                    final HlrLookupResponse hlrLookupResponse = parseResponse(httpResponse.body());
                    if (StringUtils.isNotBlank(hlrLookupResponse.error())
                            || StringUtils.isNotBlank(hlrLookupResponse.message())) {
                        throw new CarrierDataException(
                                "Received a non-success error code with status code (%d): error: %s; message: %s"
                                        .formatted(
                                                httpResponse.statusCode(),
                                                hlrLookupResponse.error(),
                                                hlrLookupResponse.message()));
                    }
                } catch (JacksonException _jacksonException) {
                    // Couldnt Parse the body move on to default message
                }
                throw new CarrierDataException(
                        "Received a non-success status code (%d)".formatted(httpResponse.statusCode()));
            }
            response = parseResponse(httpResponse.body());

        } catch (CarrierDataException | IOException e) {
            throw e;
        }

        if (response.results() == null || response.results().isEmpty()) {
            throw new CarrierDataException("No Error Reported, but results is Empty");
        }

        final HlrLookupResult result = response.results().getFirst();

        if (!result.error().equals("NONE")) {
            throw new CarrierDataException("Received a per-number error: " + result.error());
        }

        return getNetworkDetails(result)
                .map(networkDetails -> new CarrierData(
                        networkDetails.name(),
                        lineType(result.telephoneNumberType()),
                        mccFromMccMnc(networkDetails.mccmnc()),
                        mncFromMccMnc(networkDetails.mccmnc()),
                        isPorted(result.isPorted()),
                        isDisposable(result.disposableNumber())));
    }

    private Optional<Boolean> isPorted(@Nullable final String isPorted) {
        return switch (isPorted) {
            case "YES" -> Optional.of(true);
            case "NO" -> Optional.of(false);
            case null, default -> Optional.empty();
        };
    }

    private Optional<Boolean> isDisposable(@Nullable final String disposableNumber) {
        return switch (disposableNumber) {
            case "YES" -> Optional.of(true);
            case "NO" -> Optional.of(false);
            case null, default -> Optional.empty();
        };
    }

    private Optional<String> mccFromMccMnc(@Nullable final String mccMnc) {
        // Mcc always has 3 digits from start
        return Optional.ofNullable(StringUtils.stripToNull(mccMnc)).map(trimmedMccMnc -> {
            return trimmedMccMnc.substring(0, 3);
        });
    }

    private Optional<String> mncFromMccMnc(@Nullable final String mccMnc) {
        // Mnc is the rest of the string after 3 digits of mcc
        return Optional.ofNullable(StringUtils.stripToNull(mccMnc)).map(trimmedMccMnc -> {
            return trimmedMccMnc.substring(3);
        });
    }

    static CarrierData.LineType lineType(@Nullable String lineType) {
        return switch (lineType) {
            case "MOBILE" -> CarrierData.LineType.MOBILE;
            case "LANDLINE" -> CarrierData.LineType.LANDLINE;
            case "VOIP" -> CarrierData.LineType.FIXED_VOIP;
            case "UNKNOWN" -> CarrierData.LineType.UNKNOWN;
            case null -> CarrierData.LineType.UNKNOWN;
            default -> CarrierData.LineType.OTHER;
        };
    }

    private HlrLookupResponse parseResponse(final String responseJson) throws JacksonException {
        return objectMapper.readValue(responseJson, HlrLookupResponse.class);
    }

    static Optional<NetworkDetails> getNetworkDetails(final HlrLookupResult lookupResult) {
        if (lookupResult.currentNetwork().equals("AVAILABLE")) {
            return Optional.of(lookupResult.currentNetworkDetails());
        } else if (lookupResult.originalNetwork().equals("AVAILABLE")) {
            return Optional.of(lookupResult.originalNetworkDetails());
        }
        return Optional.empty();
    }

}
