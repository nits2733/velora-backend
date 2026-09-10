package com.velora.backend.dto.booking;

import com.velora.backend.entity.professional.AvailabilityStatus;

import java.math.BigDecimal;

public record ProfessionalMatchResponse(
        Long professionalId,
        String fullName,
        String specialization,
        String city,
        Integer yearsExperience,
        BigDecimal averageRating,
        int ratingCount,
        AvailabilityStatus availabilityStatus,
        int score
) {
}
