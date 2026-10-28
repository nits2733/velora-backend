package com.velora.backend.dto.vision;

public enum BudgetTier {
    BUDGET_FRIENDLY("Achieve the look with affordable but well-chosen finishes."),
    MID_RANGE("Use good-quality mid-range finishes throughout."),
    PREMIUM_LUXURY("Use premium, high-end finishes throughout.");

    private final String phrase;

    BudgetTier(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
