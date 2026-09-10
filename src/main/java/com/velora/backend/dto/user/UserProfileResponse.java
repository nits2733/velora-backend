package com.velora.backend.dto.user;

import com.velora.backend.entity.user.Role;

import java.time.Instant;

public record UserProfileResponse(
        Long id,
        String email,
        String fullName,
        String phone,
        String avatarUrl,
        Role role,
        Instant createdAt,
        ProfessionalProfileResponse professionalProfile
) {
}
