package com.velora.backend.dto.user;

import com.velora.backend.entity.AvailabilityStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * All fields are optional - only non-null values are applied.
 * bio/yearsExperience/specialization/city/availabilityStatus only take effect for
 * PROFESSIONAL accounts.
 */
public record UpdateProfileRequest(
        @Size(max = 150) String fullName,
        @Size(max = 20) String phone,
        @Size(max = 1000) String avatarUrl,
        @Size(max = 2000) String bio,
        @Min(0) @Max(80) Integer yearsExperience,
        @Size(max = 150) String specialization,
        @Size(max = 100) String city,
        AvailabilityStatus availabilityStatus
) {
}
