package com.velora.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.resend")
@Getter
@Setter
public class ResendProperties {

    private String apiKey;
    private String fromEmail = "onboarding@resend.dev";
    private String frontendUrl = "http://localhost:3000";
    private String resetUrlTemplate = "http://localhost:3000/reset-password?token=%s";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
