package com.velora.backend.dto.vision;

public enum RoomType {
    LIVING_ROOM("living room"),
    BEDROOM("bedroom"),
    KITCHEN("kitchen"),
    BATHROOM("bathroom"),
    DINING_ROOM("dining room"),
    HOME_OFFICE("home office"),
    OTHER("room");

    private final String phrase;

    RoomType(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
