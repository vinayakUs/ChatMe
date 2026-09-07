package com.example.service.entities;

import com.example.service.util.E164;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record CreateVerificationSessionRequest(

    @NotBlank 
    @E164 
    @JsonProperty 
    String number,

    @JsonUnwrapped 
    @Valid 
    UpdateVerificationSessionRequest updateVerificationSessionRequest

) {
}