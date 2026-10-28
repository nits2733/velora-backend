package com.velora.backend.dto.vision;

public enum DesignStyle {
    MODERN_LUXURY("modern luxury"),
    MINIMALIST("minimalist"),
    SCANDINAVIAN("Scandinavian"),
    CONTEMPORARY("contemporary"),
    TRADITIONAL_INDIAN("traditional Indian"),
    JAPANDI("Japandi"),
    INDUSTRIAL("industrial"),
    BOHEMIAN("bohemian"),
    CLASSIC_LUXURY("classic luxury");

    private final String phrase;

    DesignStyle(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
