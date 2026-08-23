package com.velora.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    private int forgotPasswordCapacity = 5;
    private Duration forgotPasswordDuration = Duration.ofMinutes(15);

    private int mediaUploadCapacity = 20;
    private Duration mediaUploadDuration = Duration.ofHours(1);
}
