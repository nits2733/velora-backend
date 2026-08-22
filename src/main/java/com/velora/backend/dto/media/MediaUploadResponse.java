package com.velora.backend.dto.media;

public record MediaUploadResponse(
        String secureUrl,
        String publicId,
        String format,
        Long bytes,
        Integer width,
        Integer height
) {
}
