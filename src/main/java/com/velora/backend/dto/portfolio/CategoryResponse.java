package com.velora.backend.dto.portfolio;

import com.velora.backend.entity.portfolio.ServiceGroup;

public record CategoryResponse(
        Long id,
        String name,
        String description,
        ServiceGroup serviceGroup
) {
}
