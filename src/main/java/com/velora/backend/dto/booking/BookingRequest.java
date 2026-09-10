package com.velora.backend.dto.booking;

import com.velora.backend.entity.booking.BookingTimeline;
import com.velora.backend.entity.booking.RequestType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * requestType distinguishes a Full Home Services project from a standalone
 * Individual Service request - see BookingService.createBooking for the rules this
 * drives. professionalId is optional: present means the customer picked this
 * professional directly (FULL_HOME_PROJECT only); absent means the customer wants
 * Velora to assign one (booking starts in PENDING_ASSIGNMENT until an admin assigns
 * a professional). categoryId/preferredStyle/budgetMin/budgetMax/location feed
 * ProfessionalMatchingService's scoring and are required for INDIVIDUAL_SERVICE.
 * inspirationImageUrls are optional moodboard/reference photos uploaded via
 * POST /api/uploads beforehand.
 */
public record BookingRequest(
        @NotNull RequestType requestType,
        Long professionalId,
        Long portfolioItemId,
        @NotNull @Future Instant scheduledAt,
        @Size(max = 1000) String notes,
        Long categoryId,
        @Size(max = 100) String preferredStyle,
        @DecimalMin("0") BigDecimal budgetMin,
        @DecimalMin("0") BigDecimal budgetMax,
        BookingTimeline preferredTimeline,
        @Size(max = 150) String location,
        List<@Size(max = 1000) String> inspirationImageUrls
) {
}
