package dev.b2tclient.common.social;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FriendBook {
    private final Map<String, FriendEntry> byName = new LinkedHashMap<>();

    public FriendEntry put(FriendEntry entry) {
        byName.put(key(entry.name()), entry);
        return entry;
    }

    public Optional<FriendEntry> findByName(String name) {
        return Optional.ofNullable(byName.get(key(name)));
    }

    public Optional<FriendEntry> findByUuid(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return byName.values().stream().filter(entry -> uuid.equals(entry.uuid())).findFirst();
    }

    public boolean remove(String name) {
        return byName.remove(key(name)) != null;
    }

    public List<FriendEntry> all() {
        return Collections.unmodifiableList(new ArrayList<>(byName.values()));
    }

    public void replaceAll(Collection<FriendEntry> replacements) {
        byName.clear();
        if (replacements != null) {
            replacements.forEach(this::put);
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
