package dev.sealedclient.core;

public enum Category {
    HUD("HUD"),
    COMBAT("Combat"),
    VISUAL("Visual"),
    MOVEMENT("Movement"),
    UTILITY("Utility");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
