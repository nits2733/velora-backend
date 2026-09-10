package com.velora.backend.dto.professional;

import com.velora.backend.entity.professional.AvailabilityStatus;

import java.math.BigDecimal;

public record ProfessionalPublicProfileResponse(
        Long id,
        String fullName,
        String bio,
        Integer yearsExperience,
        String specialization,
        String city,
        AvailabilityStatus availabilityStatus,
        BigDecimal averageRating,
        int ratingCount
) {
}
