package com.velora.backend.dto.vision;

public enum ColorPalette {
    BEIGE_WALNUT("warm beige and walnut wood palette"),
    WHITE_OAK("white and light oak palette"),
    SAGE_CREAM("soft sage green and cream palette"),
    GREY_WHITE("grey and white palette"),
    CHARCOAL_GOLD("charcoal and brushed gold palette"),
    EARTHY_NEUTRALS("earthy neutral palette"),
    CUSTOM(null);

    private final String phrase;

    ColorPalette(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
