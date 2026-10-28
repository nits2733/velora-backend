package com.velora.backend.dto.booking;

import com.velora.backend.entity.booking.BookingStatus;

import java.util.Map;

/**
 * Per-status totals for the caller's bookings, for tab badges. Every status is always present,
 * including zeros. {@code awaitingQuotationResponse} counts active bookings whose quotation is
 * waiting for the customer's answer; it is always 0 for professionals.
 */
public record BookingCountsResponse(
        Map<BookingStatus, Long> byStatus,
        long awaitingQuotationResponse
) {
}
