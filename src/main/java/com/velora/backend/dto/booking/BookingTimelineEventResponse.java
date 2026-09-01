package com.velora.backend.dto.booking;

import com.velora.backend.entity.BookingStatus;
import com.velora.backend.entity.TimelineEventType;

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
