package com.velora.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        String resetToken,
        String token,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Password must contain at least one letter and one digit"
        )
        String newPassword
) {
    public String effectiveToken() {
        if (resetToken != null && !resetToken.isBlank()) {
            return resetToken.trim();
        }
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        return "";
    }
}
