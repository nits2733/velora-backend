package com.velora.backend.dto.booking;

import jakarta.validation.constraints.Size;

/**
 * Optional body for cancelling a booking or declining a quotation. The whole body may be omitted,
 * and a blank reason is stored as no reason.
 */
public record ReasonRequest(
        @Size(max = 300) String reason
) {
}
