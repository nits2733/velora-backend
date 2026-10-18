package com.velora.backend.dto.portfolio;

import com.velora.backend.entity.portfolio.ServiceGroup;

import java.util.List;

public record CategoryResponse(
        Long id,
        String name,
        String description,
        ServiceGroup serviceGroup,
        String imageUrl,
        Long parentId,
        List<CategoryResponse> subcategories
) {
}
