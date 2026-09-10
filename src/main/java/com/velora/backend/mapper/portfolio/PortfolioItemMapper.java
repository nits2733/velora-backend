package com.velora.backend.mapper.portfolio;

import com.velora.backend.dto.portfolio.CategoryResponse;
import com.velora.backend.dto.portfolio.PortfolioItemResponse;
import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.entity.portfolio.InteriorDesignDetails;
import com.velora.backend.entity.portfolio.PortfolioItem;
import org.springframework.stereotype.Component;

@Component
public class PortfolioItemMapper {

    public CategoryResponse toCategoryResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDescription(),
                category.getServiceGroup(), category.getImageUrl());
    }

    public PortfolioItemSummaryResponse toSummaryResponse(PortfolioItem item) {
        InteriorDesignDetails details = item.getInteriorDesignDetails();
        return new PortfolioItemSummaryResponse(
                item.getId(),
                item.getTitle(),
                item.getCategory().getName(),
                item.getCoverImageUrl(),
                details != null ? details.getPriceEstimate() : null,
                details != null ? details.getStyleTag() : null
        );
    }

    public PortfolioItemResponse toResponse(PortfolioItem item) {
        InteriorDesignDetails details = item.getInteriorDesignDetails();
        return new PortfolioItemResponse(
                item.getId(),
                item.getTitle(),
                item.getDescription(),
                toCategoryResponse(item.getCategory()),
                item.getProfessional().getId(),
                item.getProfessional().getFullName(),
                item.getCoverImageUrl(),
                details != null ? details.getPriceEstimate() : null,
                details != null ? details.getStyleTag() : null,
                item.getCreatedAt()
        );
    }
}
