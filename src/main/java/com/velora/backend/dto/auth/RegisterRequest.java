package com.velora.backend.dto.auth;

import com.velora.backend.entity.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,

        @NotBlank @Size(min = 8, max = 100,
                message = "Password must be between 8 and 100 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Password must contain at least one letter and one digit")
        String password,

        @NotBlank @Size(max = 150) String fullName,

        @Size(max = 20) String phone,

        @NotNull Role role
) {
}
