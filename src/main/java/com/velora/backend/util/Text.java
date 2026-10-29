package com.velora.backend.util;

public final class Text {

    private Text() {
    }

    /** Trimmed text, or null when it is null or blank. */
    public static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
