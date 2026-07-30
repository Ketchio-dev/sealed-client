package dev.b2tclient.common.waypoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class WaypointBook {
    private final Map<String, Waypoint> entries = new LinkedHashMap<>();

    public Waypoint put(Waypoint waypoint) {
        entries.put(key(waypoint.name()), waypoint);
        return waypoint;
    }

    public Optional<Waypoint> find(String id) {
        return Optional.ofNullable(entries.get(key(id)));
    }

    public boolean remove(String id) {
        return entries.remove(key(id)) != null;
    }

    public List<Waypoint> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public void replaceAll(Collection<Waypoint> replacements) {
        entries.clear();
        if (replacements != null) {
            replacements.forEach(this::put);
        }
    }

    public List<Waypoint> inDimension(String dimension) {
        if (dimension == null) {
            return List.of();
        }
        String normalized = dimension.trim().toLowerCase(Locale.ROOT);
        return entries.values().stream()
                .filter(waypoint -> waypoint.dimension().equals(normalized))
                .toList();
    }

    public List<Waypoint> visibleFor(String server, String dimension) {
        if (server == null || dimension == null) {
            return List.of();
        }
        return entries.values().stream()
                .filter(Waypoint::visible)
                .filter(waypoint -> waypoint.server().equalsIgnoreCase(server))
                .filter(waypoint -> waypoint.dimension().equalsIgnoreCase(dimension))
                .toList();
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
