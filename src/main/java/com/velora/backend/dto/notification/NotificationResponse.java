package com.velora.backend.dto.notification;

import com.velora.backend.entity.NotificationType;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        Long relatedBookingId,
        boolean read,
        Instant createdAt
) {
}
