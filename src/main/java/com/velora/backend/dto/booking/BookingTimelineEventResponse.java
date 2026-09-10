package com.velora.backend.dto.booking;

import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.booking.TimelineEventType;

import java.time.Instant;

public record BookingTimelineEventResponse(
        Long id,
        TimelineEventType eventType,
        BookingStatus fromStatus,
        BookingStatus toStatus,
        String note,
        Instant createdAt
) {
}
