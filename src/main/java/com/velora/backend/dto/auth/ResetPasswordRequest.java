package com.velora.backend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The email is required alongside the code because a 6-digit OTP is only unique scoped
 * to one user's outstanding request, not globally. Same password rules as registration -
 * they're duplicated nowhere else, so they stay in step.
 */
public record ResetPasswordRequest(
        @NotBlank @Email String email,

        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be exactly 6 digits")
        String otp,

        @NotBlank @Size(min = 8, max = 100,
                message = "Password must be between 8 and 100 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Password must contain at least one letter and one digit")
        String newPassword
) {
}
