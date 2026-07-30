package dev.b2tclient.integration;

import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OptionalIntegrationManager {
    private final Map<String, Integration> integrations = new LinkedHashMap<>();
    private final BaritoneNavigator baritone;

    public OptionalIntegrationManager() {
        Integration baritoneIntegration = detect("baritone", "Baritone");
        baritone = createBaritone(baritoneIntegration);
        detect("viafabricplus", "ViaFabricPlus");
        detect("sodium", "Sodium");
    }

    public Integration integration(String modId) {
        return integrations.getOrDefault(
                modId,
                new Integration(modId, modId, false, "")
        );
    }

    public Map<String, Integration> all() {
        return Map.copyOf(integrations);
    }

    public BaritoneNavigator baritone() {
        return baritone;
    }

    private Integration detect(String modId, String displayName) {
        var container = FabricLoader.getInstance().getModContainer(modId);
        Integration integration = new Integration(
                modId,
                displayName,
                container.isPresent(),
                container.map(value -> value.getMetadata().getVersion().getFriendlyString())
                        .orElse("")
        );
        integrations.put(modId, integration);
        return integration;
    }

    private static BaritoneNavigator createBaritone(Integration integration) {
        if (!integration.available()) {
            return BaritoneNavigator.unavailable(
                    "",
                    "Baritone is not installed; install its matching Fabric mod separately"
            );
        }
        try {
            return new BaritoneApiNavigator(integration.version());
        } catch (LinkageError | RuntimeException exception) {
            return BaritoneNavigator.unavailable(
                    integration.version(),
                    "Installed Baritone API is incompatible ("
                            + exception.getClass().getSimpleName()
                            + ")"
            );
        }
    }

    public record Integration(
            String modId,
            String displayName,
            boolean available,
            String version
    ) {
    }
}
