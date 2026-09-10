package com.velora.backend.dto.auth;

import com.velora.backend.entity.user.Role;

/**
 * Returned by register, login and refresh alike, so a client has one shape to handle.
 * <p>
 * The refresh token is the only copy that will ever exist - it is stored hashed - so a
 * client that discards it has to make the user log in again.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UserSummary user
) {
    public record UserSummary(
            Long id,
            String email,
            String fullName,
            Role role
    ) {
    }

    public static AuthResponse of(String accessToken, String refreshToken,
                                   Long userId, String email, String fullName, Role role) {
        return new AuthResponse(accessToken, refreshToken, "Bearer",
                new UserSummary(userId, email, fullName, role));
    }
}
