package com.velora.backend.dto.quotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Line items may be empty while a professional is still drafting a quotation -
 * {@link com.velora.backend.service.quotation.QuotationService#send} is what enforces
 * that at least one line item exists before it can be sent to the customer.
 * <p>
 * {@code validUntil} is optional and informational: it tells the customer how long the prices hold,
 * but accepting after it is not blocked. A sent quotation cannot be edited, so it is fixed at send time.
 */
public record SaveQuotationRequest(
        @Size(max = 2000) String notes,
        @NotNull List<@Valid QuotationLineItemRequest> lineItems,
        @Future Instant validUntil
) {
}
