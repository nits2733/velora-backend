package com.velora.backend.dto.quotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Line items may be empty while a professional is still drafting a quotation -
 * {@link com.velora.backend.service.quotation.QuotationService#send} is what enforces
 * that at least one line item exists before it can be sent to the customer.
 */
public record SaveQuotationRequest(
        @Size(max = 2000) String notes,
        @NotNull List<@Valid QuotationLineItemRequest> lineItems
) {
}
