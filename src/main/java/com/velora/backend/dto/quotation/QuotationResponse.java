package com.velora.backend.dto.quotation;

import com.velora.backend.entity.quotation.QuotationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record QuotationResponse(
        Long id,
        Long bookingId,
        QuotationStatus status,
        String notes,
        BigDecimal totalAmount,
        List<QuotationLineItemResponse> lineItems,
        Instant createdAt,
        Instant updatedAt
) {
}
