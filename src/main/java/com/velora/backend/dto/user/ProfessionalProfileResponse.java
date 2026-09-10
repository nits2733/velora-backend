package com.velora.backend.dto.user;

import com.velora.backend.entity.professional.AvailabilityStatus;

import java.math.BigDecimal;

public record ProfessionalProfileResponse(
        String bio,
        Integer yearsExperience,
        String specialization,
        String city,
        AvailabilityStatus availabilityStatus,
        BigDecimal averageRating,
        int ratingCount
) {
}
