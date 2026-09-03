package com.velora.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.otp")
@Getter
@Setter
public class OtpProperties {

    private int expiryMinutes = 5;
    private int maxAttempts = 5;
    private int cooldownSeconds = 60;
    private int length = 6;
}
