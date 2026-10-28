package com.velora.backend.service.vision;

import com.velora.backend.config.VisionProperties;
import com.velora.backend.dto.vision.ColorPalette;
import com.velora.backend.dto.vision.Preservation;
import com.velora.backend.dto.vision.VisionFeature;
import com.velora.backend.dto.vision.VisionPreferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class VisionPromptBuilder {

    public static final int MAX_FREE_TEXT = 300;
    static final String ADDITIONAL_LABEL = "Additional customer preference:";
    static final String COLOUR_LABEL = "Additional customer colour preference:";
    // Prompt wording and the preservation strengths (0.30/0.50/0.70, see VisionProperties) are UNCALIBRATED
    // hypotheses pending evaluation on real room photos; nothing here is evidence of improved quality.
    static final String LAYOUT_NEGATIVE = "different room layout, moved or resized windows and doors, ";
    static final String NEGATIVE_PROMPT = "people, distorted geometry, warped walls, extra or missing legs, "
            + "floating furniture, melted objects, text, watermark, blurry, low quality";
    static final String PHOTOREALISM = "Realistic proportions, natural materials and natural lighting, "
            + "professional interior photography.";

    private final VisionProperties props;

    public BuiltPrompt build(VisionPreferences prefs) {
        List<String> sections = new ArrayList<>();

        StringBuilder head = new StringBuilder("Interior photograph of the same ");
        head.append(prefs.roomType() != null ? prefs.roomType().phrase() : "room").append(", renovated");
        if (prefs.style() != null) {
            String style = prefs.style().phrase();
            head.append(" in ").append(startsWithVowel(style) ? "an " : "a ").append(style).append(" style");
        }
        sections.add(head.append('.').toString());

        if (prefs.colorPalette() != null && prefs.colorPalette() != ColorPalette.CUSTOM) {
            String palette = prefs.colorPalette().phrase();
            sections.add("Use " + (startsWithVowel(palette) ? "an " : "a ") + palette + ".");
        }
        if (prefs.budgetTier() != null) {
            sections.add(prefs.budgetTier().phrase());
        }
        if (prefs.features() != null) {
            EnumSet<VisionFeature> set = EnumSet.noneOf(VisionFeature.class);
            prefs.features().stream().filter(Objects::nonNull).forEach(set::add);
            if (!set.isEmpty()) {
                sections.add("Include " + set.stream().map(VisionFeature::phrase)
                        .collect(Collectors.joining(", ")) + ".");
            }
        }
        if (prefs.preservation() != null) {
            sections.add(prefs.preservation().phrase());
        }
        sections.add(PHOTOREALISM);

        if (prefs.colorPalette() == ColorPalette.CUSTOM) {
            String custom = sanitise(prefs.customColorDescription(), 60);
            if (!custom.isEmpty()) {
                sections.add(COLOUR_LABEL + " " + custom + ".");
            }
        }

        String extra = sanitise(prefs.additionalInstructions(), MAX_FREE_TEXT);
        if (!extra.isEmpty()) {
            sections.add(ADDITIONAL_LABEL + " " + extra);
        }
        return new BuiltPrompt(String.join(" ", sections), negativePrompt(prefs.preservation()));
    }

    public String negativePrompt(Preservation preservation) {
        if (!props.isNegativePromptEnabled()) {
            return null;
        }
        return preservation == Preservation.CREATIVE ? NEGATIVE_PROMPT : LAYOUT_NEGATIVE + NEGATIVE_PROMPT;
    }

    private static boolean startsWithVowel(String s) {
        return !s.isEmpty() && "aeiouAEIOU".indexOf(s.charAt(0)) >= 0;
    }

    public static String sanitise(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\s]]", "").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength).trim();
        }
        return cleaned;
    }

}
