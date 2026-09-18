package com.example.service.telephony.hlrlookup;

import java.util.List;

import io.github.resilience4j.core.lang.Nullable;

/**
 * Response from HLR API
 * Handles both error and success response in same class  
 * HlrLookupResponse
 */
public record HlrLookupResponse(
    @Nullable List<HlrLookupResult> results,
    @Nullable String error,
    @Nullable String message

) {
}