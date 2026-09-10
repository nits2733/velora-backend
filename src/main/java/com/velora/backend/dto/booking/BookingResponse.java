package com.velora.backend.dto.booking;

import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.booking.BookingTimeline;
import com.velora.backend.entity.booking.RequestType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long id,
        RequestType requestType,
        Long customerId,
        String customerName,
        ProfessionalSummaryResponse professional,
        PortfolioItemSummaryResponse portfolioItem,
        Long categoryId,
        String categoryName,
        String preferredStyle,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        BookingTimeline preferredTimeline,
        String location,
        Instant scheduledAt,
        BookingStatus status,
        String notes,
        List<String> inspirationImageUrls,
        Instant createdAt
) {
}
