package dev.sealedclient.common.module;

import java.util.LinkedHashMap;
import java.util.Map;

public record ModuleSnapshot(
        boolean enabled,
        boolean favorite,
        int keyCode,
        Map<String, String> settings
) {
    public ModuleSnapshot {
        settings = Map.copyOf(new LinkedHashMap<>(settings));
    }
}
