package com.velora.backend.config;

import com.cloudinary.Cloudinary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class CloudinaryConfig {

    @Value("${app.cloudinary.url:}")
    private String cloudinaryUrl;

    @Bean
    public Cloudinary cloudinary() {
        if (cloudinaryUrl != null && !cloudinaryUrl.isBlank()) {
            return new Cloudinary(cloudinaryUrl);
        }
        // Fallback default for local dev/testing where mock is used
        Map<String, String> config = new HashMap<>();
        config.put("cloud_name", "velora-dev");
        config.put("api_key", "mock-key");
        config.put("api_secret", "mock-secret");
        return new Cloudinary(config);
    }
}
