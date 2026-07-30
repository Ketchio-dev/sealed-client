package dev.b2tclient.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class WaypointManager {
    private final Map<String, Waypoint> waypoints = new LinkedHashMap<>();

    public boolean add(Waypoint waypoint) {
        return waypoints.put(key(waypoint.name()), waypoint) == null;
    }

    public boolean remove(String name) {
        return waypoints.remove(key(name)) != null;
    }

    public Optional<Waypoint> find(String name) {
        return Optional.ofNullable(waypoints.get(key(name)));
    }

    public Collection<Waypoint> all() {
        return List.copyOf(waypoints.values());
    }

    public List<Waypoint> visibleFor(String server, String dimension) {
        return waypoints.values().stream()
                .filter(Waypoint::visible)
                .filter(waypoint -> waypoint.server().equalsIgnoreCase(server))
                .filter(waypoint -> waypoint.dimension().equals(dimension))
                .toList();
    }

    public void replaceAll(Collection<Waypoint> replacements) {
        waypoints.clear();
        if (replacements != null) {
            replacements.forEach(this::add);
        }
    }

    private static String key(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Waypoint name cannot be blank");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
