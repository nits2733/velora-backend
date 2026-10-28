package com.velora.backend.dto.vision;

public enum VisionFeature {
    SOFA_SEATING("a comfortable sofa and seating arrangement"),
    TV_UNIT("a tasteful TV unit"),
    WARDROBE_STORAGE("built-in wardrobes and smart storage"),
    LIGHTING("well-planned layered lighting"),
    CURTAINS("elegant curtains"),
    FALSE_CEILING("a false ceiling with integrated lighting"),
    INDOOR_PLANTS("indoor plants"),
    WALL_PANELS("wall panelling"),
    FLOORING("new flooring"),
    DECORATIVE_ELEMENTS("tasteful decorative elements");

    private final String phrase;

    VisionFeature(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
