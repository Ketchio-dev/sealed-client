package dev.b2tclient.common.waypoint;

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
        name = requireText(name, "name");
        server = Objects.requireNonNullElse(server, "singleplayer").trim();
        dimension = Objects.requireNonNullElse(dimension, "minecraft:overworld").trim();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Waypoint coordinates must be finite");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
