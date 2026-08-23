package com.velora.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
@ConfigurationProperties(prefix = "app.media-upload")
@Getter
@Setter
public class MediaUploadProperties {

    private long maxFileSize = 10 * 1024 * 1024; // 10MB default
    private Set<String> allowedContentTypes = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );
}
