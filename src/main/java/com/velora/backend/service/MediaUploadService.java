package com.velora.backend.service;

import com.velora.backend.dto.media.MediaUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface MediaUploadService {

    /**
     * Securely validates and uploads an image to Cloudinary.
     *
     * @param file the multipart file to upload
     * @param folder designated Cloudinary folder (e.g. "portfolio", "avatars")
     * @return response containing secure_url, public_id, and media metadata
     */
    MediaUploadResponse uploadImage(MultipartFile file, String folder);
}
