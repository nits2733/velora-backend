package com.velora.backend.config;

import com.velora.backend.dto.vision.Preservation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Configuration
@Validated
@ConfigurationProperties(prefix = "vision")
@Getter
@Setter
public class VisionProperties {

    private int dailyLimit = 5;
    private String stylePrefix = "interior design photo, same room layout, ";
    @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 2)
    private double imageStrength = 0.35;
    private Duration staleAfter = Duration.ofMinutes(20);
    private String apiUrl = "https://api.stability.ai/v2beta/stable-image/generate";
    private String engine = "sd3";
    private String apiKey = "";
    private Duration timeout = Duration.ofSeconds(60);
    private boolean negativePromptEnabled = true;
    private Duration duplicateWindow = Duration.ofSeconds(60);
    // UNCALIBRATED: these three strengths are hypotheses; no real-image evaluation has been done yet.
    @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 2)
    private double strengthPreserveOriginal = 0.30;
    @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 2)
    private double strengthBalanced = 0.50;
    @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 2)
    private double strengthCreative = 0.70;

    @Min(1)
    private int minShortSide = 512;
    @Min(1)
    private int targetPixels = 1_000_000;
    @Min(1)
    private long maxInputPixels = 40_000_000L;
    @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0")
    private float jpegQuality = 0.92f;
    @Min(1)
    private int preprocessConcurrency = 2;
    private Duration preprocessWait = Duration.ofSeconds(5);

    public double strengthFor(Preservation preservation) {
        return switch (preservation) {
            case PRESERVE_ORIGINAL -> strengthPreserveOriginal;
            case BALANCED -> strengthBalanced;
            case CREATIVE -> strengthCreative;
        };
    }
}
