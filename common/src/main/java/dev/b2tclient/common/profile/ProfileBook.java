package dev.b2tclient.common.profile;

import dev.b2tclient.common.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ProfileBook {
    private final Map<String, ClientProfile> profiles = new LinkedHashMap<>();
    private String activeKey;

    public ClientProfile capture(String name, String serverPattern, ModuleRegistry registry) {
        ClientProfile profile = new ClientProfile(name, serverPattern, registry.snapshot());
        profiles.put(profile.key(), profile);
        if (activeKey == null) {
            activeKey = profile.key();
        }
        return profile;
    }

    public Optional<ClientProfile> find(String name) {
        return Optional.ofNullable(profiles.get(key(name)));
    }

    public boolean activate(String name, ModuleRegistry registry) {
        ClientProfile profile = profiles.get(key(name));
        if (profile == null) {
            return false;
        }
        try {
            registry.apply(profile.modules());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
        activeKey = profile.key();
        return true;
    }

    public Optional<ClientProfile> active() {
        return Optional.ofNullable(activeKey).map(profiles::get);
    }

    public List<ClientProfile> all() {
        return Collections.unmodifiableList(new ArrayList<>(profiles.values()));
    }

    /**
     * Returns the most specific bounded glob match, or the first {@code *}
     * profile when no specific pattern matches.
     */
    public Optional<ClientProfile> bestMatchForServer(String server) {
        return ServerProfileMatcher.select(profiles.values(), server);
    }

    /**
     * Reapplies the best server snapshot even when it is already marked
     * active. A new connection is a session boundary, so unsaved live
     * mutations from the previous connection must not bypass the profile.
     */
    public boolean activateBestMatchForServer(
            String server,
            ModuleRegistry registry
    ) {
        return bestMatchForServer(server)
                .map(profile -> activate(profile.name(), registry))
                .orElse(false);
    }

    /**
     * Removes a profile. The last remaining profile is kept: with none left
     * there would be no snapshot to fall back to on the next connection, and a
     * server match would silently do nothing.
     *
     * @return the outcome, so callers can explain a refusal rather than
     *         reporting a delete that never happened
     */
    public DeleteResult delete(String name) {
        String target = key(name);
        if (!profiles.containsKey(target)) {
            return DeleteResult.NOT_FOUND;
        }
        if (profiles.size() <= 1) {
            return DeleteResult.LAST_PROFILE;
        }
        profiles.remove(target);
        if (target.equals(activeKey)) {
            // Hand active status to whatever remains rather than leaving the
            // book pointing at a profile that no longer exists.
            activeKey = profiles.keySet().stream().findFirst().orElse(null);
        }
        return DeleteResult.DELETED;
    }

    /** Outcome of a {@link #delete(String)} attempt. */
    public enum DeleteResult {
        DELETED,
        NOT_FOUND,
        /** Refused: a client must always retain at least one profile. */
        LAST_PROFILE
    }

    public void replaceAll(Collection<ClientProfile> replacements, String activeName) {
        profiles.clear();
        if (replacements != null) {
            replacements.forEach(profile -> profiles.put(profile.key(), profile));
        }
        String requested = activeName == null ? "" : key(activeName);
        activeKey = profiles.containsKey(requested)
                ? requested
                : profiles.keySet().stream().findFirst().orElse(null);
    }

    private static String key(String name) {
        return Objects.requireNonNull(name, "name").trim().toLowerCase(Locale.ROOT);
    }
}
