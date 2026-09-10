package com.velora.backend.service.upload;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Cloudinary-backed storage, active whenever a {@code CLOUDINARY_URL} is configured.
 * Preferred over {@link LocalFileStorageService} in any deployment without a persistent
 * disk - uploads otherwise vanish on the next redeploy.
 */
@Service
@ConditionalOnExpression("!'${CLOUDINARY_URL:}'.isBlank()")
@Slf4j
public class CloudinaryFileStorageService implements FileStorageService {

    private final Cloudinary cloudinary;

    public CloudinaryFileStorageService(@Value("${CLOUDINARY_URL}") String cloudinaryUrl) {
        this.cloudinary = new Cloudinary(cloudinaryUrl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String store(MultipartFile file, String subfolder) {
        ImageUploadValidator.validate(file);

        try {
            Map<String, Object> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", "velora/" + subfolder,
                    "resource_type", "image"
            ));
            String secureUrl = (String) result.get("secure_url");
            if (secureUrl == null) {
                throw new IllegalStateException("Cloudinary upload succeeded but returned no secure_url");
            }
            return secureUrl;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to upload file to Cloudinary", e);
        }
    }
}
