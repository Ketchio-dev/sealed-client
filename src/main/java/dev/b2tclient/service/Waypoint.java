package dev.b2tclient.service;

import java.util.Objects;

public record Waypoint(
        String name,
        String server,
        String dimension,
        double x,
        double y,
        double z,
        int color,
        boolean visible
) {
    public Waypoint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Waypoint name cannot be blank");
        }
        name = name.trim();
        server = Objects.requireNonNullElse(server, "singleplayer");
        dimension = Objects.requireNonNullElse(dimension, "minecraft:overworld");
    }
}
