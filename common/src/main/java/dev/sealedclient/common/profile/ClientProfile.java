package dev.sealedclient.common.profile;

import dev.sealedclient.common.module.ModuleSnapshot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record ClientProfile(
        String name,
        String serverPattern,
        Map<String, ModuleSnapshot> modules
) {
    public ClientProfile {
        name = Objects.requireNonNull(name, "name").trim();
        serverPattern = Objects.requireNonNull(serverPattern, "serverPattern").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
        modules = Map.copyOf(new LinkedHashMap<>(modules));
    }

    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }
}
