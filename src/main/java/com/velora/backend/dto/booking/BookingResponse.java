package com.velora.backend.dto.booking;

import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.booking.BookingTimeline;
import com.velora.backend.entity.booking.RequestType;
import com.velora.backend.entity.quotation.QuotationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code quotationStatus} and {@code quotationTotal} are null when there is no quotation, and also
 * when it is still a {@code DRAFT} and the viewer is the customer (a draft is not offered yet).
 * {@code reviewed} is true once the customer has reviewed this booking.
 * {@code quotationValidUntil} is informational and follows the quotation's visibility.
 * {@code cancellationReason} is hidden from the assigned professional.
 */
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
        Instant createdAt,
        Instant updatedAt,
        QuotationStatus quotationStatus,
        BigDecimal quotationTotal,
        boolean reviewed,
        Instant quotationValidUntil,
        String cancellationReason
) {
}
