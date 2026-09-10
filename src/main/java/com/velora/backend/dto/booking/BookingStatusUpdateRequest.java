package com.velora.backend.dto.booking;

import com.velora.backend.entity.booking.BookingStatus;
import jakarta.validation.constraints.NotNull;

public record BookingStatusUpdateRequest(
        @NotNull BookingStatus status
) {
}
