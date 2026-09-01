package com.velora.backend.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints the opaque tokens (refresh, password reset) and hashes them for storage.
 * <p>
 * The raw token is returned to the caller exactly once and never persisted; only the
 * digest is. Lookup therefore hashes the presented token and searches by digest, which
 * is why a plain SHA-256 is correct here rather than BCrypt: these are 256 bits of
 * {@link SecureRandom} output, not user-chosen passwords, so there's nothing to brute
 * force, and the lookup has to be deterministic.
 */
@Component
public class SecureTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /** A fresh URL-safe token. Hand this to the client; store {@link #hash(String)}. */
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * A fresh 6-digit numeric code, zero-padded, for OTP-style delivery (email/SMS)
     * where a user types the value back in rather than following a link. Hash it with
     * {@link #hash(String)} for storage exactly like {@link #generate()}'s output.
     */
    public String generateNumericCode() {
        int value = secureRandom.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
