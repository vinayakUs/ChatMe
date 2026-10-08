package com.example.service.telephony.hlrlookup;

import java.util.List;

import io.github.resilience4j.core.lang.Nullable;

/**
 * HlrLookupResponse
 */
public record HlrLookupResponse(
    @Nullable List<HlrLookupResult> results,
    @Nullable String error,
    @Nullable String message

) {
}