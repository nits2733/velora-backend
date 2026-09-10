package com.velora.backend.dto.professional;

import com.velora.backend.entity.professional.AvailabilityStatus;

import java.math.BigDecimal;

/**
 * Directory-listing shape: everything needed to render a professional card, minus the
 * bio - same summary-vs-detail split the portfolio catalog uses, so a long bio never
 * bloats a 50-item page. Fetch {@link ProfessionalPublicProfileResponse} via
 * {@code GET /api/professionals/{id}} for the full profile.
 */
public record ProfessionalSummaryResponse(
        Long id,
        String fullName,
        String specialization,
        String city,
        Integer yearsExperience,
        AvailabilityStatus availabilityStatus,
        BigDecimal averageRating,
        int ratingCount
) {
}
