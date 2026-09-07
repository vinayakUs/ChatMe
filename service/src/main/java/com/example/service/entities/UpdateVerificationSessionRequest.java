package com.example.service.entities;

/**
 * UpdateVerificationSessionRequest
 */
public record UpdateVerificationSessionRequest(
        String captcha,
        String mmc,
        String mnc) {
}