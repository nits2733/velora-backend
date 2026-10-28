package com.velora.backend.dto.vision;

import java.util.List;

public record VisionPreferences(
        RoomType roomType,
        DesignStyle style,
        ColorPalette colorPalette,
        String customColorDescription,
        BudgetTier budgetTier,
        List<VisionFeature> features,
        Preservation preservation,
        String additionalInstructions) {
}
