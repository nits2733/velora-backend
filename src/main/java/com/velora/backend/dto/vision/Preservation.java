package com.velora.backend.dto.vision;

public enum Preservation {
    PRESERVE_ORIGINAL("Preserve the original architecture, windows, doors, camera perspective and overall layout exactly."),
    BALANCED("Keep the main layout and the window and door openings, but allow furniture and finish changes."),
    CREATIVE("Keep it recognisably the same room, but the layout and structure may change more freely.");

    private final String phrase;

    Preservation(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
