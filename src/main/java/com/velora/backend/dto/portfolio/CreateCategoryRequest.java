package com.velora.backend.dto.portfolio;

import com.velora.backend.entity.portfolio.ServiceGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        ServiceGroup serviceGroup,
        @Size(max = 1000) String imageUrl,
        Long parentId
) {
}
