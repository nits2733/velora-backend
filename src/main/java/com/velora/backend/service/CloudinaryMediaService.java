package com.velora.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.exception.MediaUploadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudinaryMediaService implements MediaUploadService {

    private final Cloudinary cloudinary;
    private final com.velora.backend.config.MediaUploadProperties mediaUploadProperties;

    @Override
    public MediaUploadResponse uploadImage(MultipartFile file, String folder) {
        validateFile(file);

        String targetFolder = (folder != null && !folder.isBlank())
                ? "velora/" + folder.trim()
                : "velora/uploads";

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = ObjectUtils.asMap(
                    "folder", targetFolder,
                    "resource_type", "image",
                    "use_filename", false,
                    "unique_filename", true
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().upload(file.getBytes(), params);

            String secureUrl = (String) result.get("secure_url");
            String publicId = (String) result.get("public_id");
            String format = (String) result.get("format");

            Long bytes = result.get("bytes") instanceof Number n ? n.longValue() : file.getSize();
            Integer width = result.get("width") instanceof Number n ? n.intValue() : null;
            Integer height = result.get("height") instanceof Number n ? n.intValue() : null;

            log.info("Successfully uploaded media asset with public_id={} to folder={}", publicId, targetFolder);
            return new MediaUploadResponse(secureUrl, publicId, format, bytes, width, height);

        } catch (IOException e) {
            log.error("Failed to read file bytes for upload: {}", e.getMessage());
            throw new MediaUploadException("Failed to read uploaded file", e);
        } catch (Exception e) {
            log.error("Cloudinary upload failed: {}", e.getMessage());
            throw new MediaUploadException("Failed to upload media to cloud storage", e);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MediaUploadException("Please select a valid non-empty file to upload");
        }

        if (file.getSize() > mediaUploadProperties.getMaxFileSize()) {
            long maxMb = mediaUploadProperties.getMaxFileSize() / (1024 * 1024);
            throw new MediaUploadException("File size (" + (file.getSize() / (1024 * 1024)) + "MB) exceeds maximum permitted limit of " + maxMb + "MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !mediaUploadProperties.getAllowedContentTypes().contains(contentType.toLowerCase())) {
            throw new MediaUploadException("Invalid file type '" + contentType + "'. Only JPEG, PNG, and WebP images are allowed.");
        }
    }
}
