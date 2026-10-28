package com.velora.backend.service.media;

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

    /** Runs the same emptiness, size and content-type checks as {@link #uploadImage} without uploading. */
    void validate(MultipartFile file);


    /** Uploads already-in-memory image bytes (e.g. AI-generated output); no multipart validation applies. */
    MediaUploadResponse uploadImageBytes(byte[] bytes, String contentType, String folder);
}
