package com.velora.backend.service.upload;

import com.velora.backend.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Disk-backed fallback, active whenever no {@link CloudinaryFileStorageService} is
 * configured (see its {@code @ConditionalOnExpression}). Fine for local development or
 * any deployment with a persistent volume; production should have a Cloudinary account
 * wired up so uploads survive a redeploy.
 */
@Service
@ConditionalOnMissingBean(ignored = LocalFileStorageService.class, value = FileStorageService.class)
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {

    private final StorageProperties storageProperties;

    @Override
    public String store(MultipartFile file, String subfolder) {
        ImageUploadValidator.validate(file);
        String filename = UUID.randomUUID() + ImageUploadValidator.extensionFor(file.getContentType());

        try {
            Path targetDir = Path.of(storageProperties.getUploadDir(), subfolder);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(filename);
            file.transferTo(targetFile);
            return storageProperties.getBaseUrl() + "/" + subfolder + "/" + filename;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded file", e);
        }
    }
}
