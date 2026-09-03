package com.velora.backend.service;

import com.velora.backend.config.RateLimitProperties;
import com.velora.backend.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory Token Bucket rate limiter to protect sensitive/heavy
 * endpoints (such as password recovery and media uploads) against abuse.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final RateLimitProperties rateLimitProperties;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Rate limit for forgot password: max requests per duration per IP.
     */
    public void checkForgotPasswordRateLimit(String clientIp) {
        String key = "forgot-password:" + clientIp;
        if (!tryAcquire(key, rateLimitProperties.getForgotPasswordCapacity(), rateLimitProperties.getForgotPasswordDuration())) {
            throw new RateLimitExceededException("Too many password reset requests. Please wait a few minutes before trying again.");
        }
    }

    /**
     * Rate limit for media uploads: max uploads per duration per user/IP.
     */
    public void checkMediaUploadRateLimit(String clientIdentifier) {
        String key = "media-upload:" + clientIdentifier;
        if (!tryAcquire(key, rateLimitProperties.getMediaUploadCapacity(), rateLimitProperties.getMediaUploadDuration())) {
            throw new RateLimitExceededException("Upload limit exceeded. Please wait before uploading more files.");
        }
    }

    /**
     * Rate limit for OTP verification: max 10 attempts per minute per IP.
     */
    public void checkOtpVerifyRateLimit(String clientIp) {
        String key = "otp-verify:" + clientIp;
        if (!tryAcquire(key, rateLimitProperties.getOtpVerifyCapacity(), rateLimitProperties.getOtpVerifyDuration())) {
            throw new RateLimitExceededException("Too many verification attempts. Please wait a minute before trying again.");
        }
    }

    public boolean tryAcquire(String key, int capacity, Duration refillPeriod) {
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPeriod));
        return bucket.tryConsume();
    }

    /**
     * Periodically evict stale rate limit buckets every 30 minutes to prevent memory leaks.
     */
    @Scheduled(fixedRate = 1800000)
    public void evictStaleBuckets() {
        int initialSize = buckets.size();
        buckets.entrySet().removeIf(entry -> entry.getValue().isStale());
        int removed = initialSize - buckets.size();
        if (removed > 0) {
            log.debug("Evicted {} stale rate limiter buckets", removed);
        }
    }

    /**
     * Clear all buckets (useful for unit/integration tests).
     */
    public void clear() {
        buckets.clear();
    }

    private static class TokenBucket {
        private final int capacity;
        private final long refillIntervalNanos;
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessNanos;

        public TokenBucket(int capacity, Duration refillPeriod) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.refillIntervalNanos = refillPeriod.toNanos();
            this.lastRefillNanos = System.nanoTime();
            this.lastAccessNanos = System.nanoTime();
        }

        public synchronized boolean tryConsume() {
            lastAccessNanos = System.nanoTime();
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        public synchronized boolean isStale() {
            // Bucket considered stale if idle for more than 1 hour and fully refilled
            return (System.nanoTime() - lastAccessNanos) > Duration.ofHours(1).toNanos();
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos > 0) {
                double tokensToAdd = (double) elapsedNanos / refillIntervalNanos * capacity;
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefillNanos = now;
            }
        }
    }
}
