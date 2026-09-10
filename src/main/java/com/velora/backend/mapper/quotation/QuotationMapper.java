package com.velora.backend.mapper.quotation;

import com.velora.backend.dto.quotation.QuotationLineItemResponse;
import com.velora.backend.dto.quotation.QuotationResponse;
import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationLineItem;
import org.springframework.stereotype.Component;

@Component
public class QuotationMapper {

    public QuotationResponse toResponse(Quotation quotation) {
        return new QuotationResponse(
                quotation.getId(),
                quotation.getBooking().getId(),
                quotation.getStatus(),
                quotation.getNotes(),
                quotation.getTotalAmount(),
                quotation.getLineItems().stream().map(this::toLineItemResponse).toList(),
                quotation.getCreatedAt(),
                quotation.getUpdatedAt()
        );
    }

    private QuotationLineItemResponse toLineItemResponse(QuotationLineItem item) {
        return new QuotationLineItemResponse(
                item.getId(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getAmount()
        );
    }
}
