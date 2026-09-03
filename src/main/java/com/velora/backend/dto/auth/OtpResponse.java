package com.velora.backend.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OtpResponse(
        String message,
        boolean requiresOtp
) {
}
