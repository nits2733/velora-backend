package com.velora.backend.service.upload;

import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over where uploaded files physically live, so swapping local disk for
 * S3-compatible object storage later is a new implementation, not a redesign.
 */
public interface FileStorageService {

    /**
     * Validates and persists the file, returning a publicly reachable URL.
     *
     * @param subfolder logical bucket (e.g. "portfolio-covers", "avatars", "inspiration") -
     *                   never derived from user input directly, only from a fixed enum
     */
    String store(MultipartFile file, String subfolder);
}
