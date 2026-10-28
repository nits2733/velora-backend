package com.velora.backend.service.vision;

public final class VisionSeed {

    private static final long MODULUS = 4_294_967_295L;

    private VisionSeed() {
    }

    public static long fromFingerprint(String hex) {
        if (hex == null || hex.length() < 8) {
            throw new IllegalArgumentException("Fingerprint must contain at least 8 hex characters");
        }
        for (int i = 0; i < 8; i++) {
            if (Character.digit(hex.charAt(i), 16) < 0 || hex.charAt(i) > 'f') {
                throw new IllegalArgumentException("Fingerprint is not hexadecimal");
            }
        }
        return Long.parseLong(hex.substring(0, 8), 16) % MODULUS;
    }
}
