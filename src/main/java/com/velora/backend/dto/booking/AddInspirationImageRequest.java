package com.velora.backend.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddInspirationImageRequest(
        @NotBlank @Size(max = 1000) String imageUrl
) {
}
