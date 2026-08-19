package com.velora.backend.dto.booking;

import com.velora.backend.entity.BookingStatus;
import com.velora.backend.entity.RequestType;

import java.math.BigDecimal;
import java.time.Instant;

public record BookingResponse(
        Long id,
        RequestType requestType,
        Long customerId,
        String customerName,
        Long professionalId,
        String professionalName,
        Long portfolioItemId,
        String portfolioItemTitle,
        Long categoryId,
        String categoryName,
        String preferredStyle,
        BigDecimal budget,
        String location,
        Instant scheduledAt,
        BookingStatus status,
        String notes,
        Instant createdAt
) {
}
