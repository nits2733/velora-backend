package com.velora.backend.service.upload;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/** Shared upload validation so every {@link FileStorageService} implementation enforces the same rules. */
final class ImageUploadValidator {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private ImageUploadValidator() {
    }

    static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was provided");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only JPEG, PNG, or WebP images are allowed");
        }
    }

    static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
