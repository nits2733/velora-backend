package com.velora.backend.service;

import com.velora.backend.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory Token Bucket rate limiter to protect sensitive/heavy
 * endpoints (such as password recovery and media uploads) against abuse.
 */
@Service
public class RateLimiterService {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Rate limit for forgot password: max 5 requests per 15 minutes per IP.
     */
    public void checkForgotPasswordRateLimit(String clientIp) {
        String key = "forgot-password:" + clientIp;
        if (!tryAcquire(key, 5, Duration.ofMinutes(15))) {
            throw new RateLimitExceededException("Too many password reset requests. Please wait a few minutes before trying again.");
        }
    }

    /**
     * Rate limit for media uploads: max 20 uploads per hour per user/IP.
     */
    public void checkMediaUploadRateLimit(String clientIdentifier) {
        String key = "media-upload:" + clientIdentifier;
        if (!tryAcquire(key, 20, Duration.ofHours(1))) {
            throw new RateLimitExceededException("Upload limit exceeded. Please wait before uploading more files.");
        }
    }

    public boolean tryAcquire(String key, int capacity, Duration refillPeriod) {
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPeriod));
        return bucket.tryConsume();
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

        public TokenBucket(int capacity, Duration refillPeriod) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.refillIntervalNanos = refillPeriod.toNanos();
            this.lastRefillNanos = System.nanoTime();
        }

        public synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
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
