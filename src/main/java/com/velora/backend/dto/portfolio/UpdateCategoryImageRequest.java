package com.velora.backend.dto.portfolio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * imageUrl is a Cloudinary secure_url obtained beforehand via
 * POST /api/media/upload (folder "categories") - this endpoint only attaches it to
 * the category, it does not upload anything itself.
 */
public record UpdateCategoryImageRequest(
        @NotBlank @Size(max = 1000) String imageUrl
) {
}
