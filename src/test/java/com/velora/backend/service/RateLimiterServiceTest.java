package com.velora.backend.service;

import com.velora.backend.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterServiceTest {

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService();
        rateLimiterService.clear();
    }

    @Test
    void allowsRequestsUpToConfiguredCapacity() {
        boolean first = rateLimiterService.tryAcquire("test-key", 2, Duration.ofMinutes(1));
        boolean second = rateLimiterService.tryAcquire("test-key", 2, Duration.ofMinutes(1));
        boolean third = rateLimiterService.tryAcquire("test-key", 2, Duration.ofMinutes(1));

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isFalse();
    }

    @Test
    void forgotPasswordRateLimitAllows5RequestsAndBlocksSubsequent() {
        String clientIp = "192.168.1.100";

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> rateLimiterService.checkForgotPasswordRateLimit(clientIp))
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> rateLimiterService.checkForgotPasswordRateLimit(clientIp))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Too many password reset requests");
    }

    @Test
    void mediaUploadRateLimitEnforcesLimitPerIdentifier() {
        String userId = "user-42";

        for (int i = 0; i < 20; i++) {
            assertThatCode(() -> rateLimiterService.checkMediaUploadRateLimit(userId))
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> rateLimiterService.checkMediaUploadRateLimit(userId))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Upload limit exceeded");
    }
}
