package dev.b2tclient.v26.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in, local-only presets for 26.2.
 *
 * <p>Presets are deliberately partial: a module the preset does not mention
 * keeps whatever the active profile set. Setting values are stored in the same
 * encoded string form the settings themselves serialize to, so a preset is
 * validated by exactly the same code path as a loaded config.</p>
 */
public final class PresetCatalog26 {
    public static final String LOW_LAG_UTILITY_ID = "low_lag_utility";
    public static final String TRAVEL_SAFE_ID = "travel_safe";
    public static final String CRYSTAL_PRACTICE_ID = "crystal_practice";

    private static final List<Preset> PRESETS = List.of(
            new Preset(
                    LOW_LAG_UTILITY_ID,
                    "Low-lag Utility",
                    "Keeps essential HUD information and disables costly world overlays.",
                    List.of(
                            on("fps"), on("ping"), on("tick_rate"),
                            on("coordinates"), on("server_info"), on("array_list"),
                            off("radar"), off("target_hud"), off("player_esp"),
                            off("storage_esp"), off("tracers"), off("chams"),
                            off("hole_esp"), off("block_esp"), off("xray"),
                            off("freecam"), off("trajectories"), off("new_chunks"),
                            off("stash_finder"), off("logout_spots")
                    )
            ),
            new Preset(
                    TRAVEL_SAFE_ID,
                    "Travel Safe",
                    "Travel HUD plus conservative sprint, elytra, totem, and reconnect aids.",
                    List.of(
                            on("coordinates"), on("direction"), on("speed"),
                            on("durability_warning"), on("death_position"),
                            on("totem_count"), on("server_info"), on("waypoints"),
                            on("portal_coords"),
                            patch("auto_sprint", true, Map.of("require_forward", "true")),
                            on("safe_walk"),
                            patch("elytra_swap", true, Map.of(
                                    "fall_distance", "2.0",
                                    "minimum_durability", "16",
                                    "restore_armor", "true"
                            )),
                            patch("auto_totem", true, Map.of(
                                    "health", "16.0",
                                    "replace_offhand", "true",
                                    "delay", "2"
                            )),
                            patch("auto_reconnect", true, Map.of(
                                    "delay_seconds", "15",
                                    "maximum_attempts", "5"
                            )),
                            off("ground_speed"), off("jesus"), off("fast_swim"),
                            off("step"), off("no_slow"), off("freecam")
                    )
            ),
            new Preset(
                    CRYSTAL_PRACTICE_ID,
                    "Crystal Practice",
                    "Conservative crystal setup with friend and self-safety defaults.",
                    List.of(
                            on("health"), on("armor"), on("totem_count"),
                            on("supplies"), on("target_hud"), on("totem_pop_local"),
                            on("hole_esp"), on("nametags"),
                            patch("auto_totem", true, Map.of(
                                    "health", "20.0",
                                    "replace_offhand", "true",
                                    "delay", "2"
                            )),
                            patch("auto_crystal", true, Map.of(
                                    "target_range", "8.0",
                                    "break_range", "4.5",
                                    "place_range", "4.5",
                                    "delay", "3"
                            )),
                            on("auto_weapon"), on("surround"),
                            off("kill_aura"), off("bed_aura"), off("anchor_aura"),
                            off("piston_crystal"), off("auto_trap"),
                            off("hole_fill"), off("burrow")
                    )
            )
    );

    private PresetCatalog26() {
    }

    public static List<Preset> all() {
        return PRESETS;
    }

    public static Optional<Preset> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return PRESETS.stream()
                .filter(preset -> preset.id().equals(normalized))
                .findFirst();
    }

    private static ModulePatch on(String moduleId) {
        return patch(moduleId, true, Map.of());
    }

    private static ModulePatch off(String moduleId) {
        return patch(moduleId, false, Map.of());
    }

    private static ModulePatch patch(
            String moduleId,
            boolean enabled,
            Map<String, String> settings
    ) {
        return new ModulePatch(moduleId, enabled, settings);
    }

    public record Preset(
            String id,
            String name,
            String description,
            List<ModulePatch> modules
    ) {
        public Preset {
            id = normalizeId(id);
            modules = List.copyOf(modules);
            if (name == null || name.isBlank() || description == null || description.isBlank()) {
                throw new IllegalArgumentException("Preset text fields must not be blank");
            }
        }

        /** Module ids this preset would switch on. */
        public List<String> enabledModuleIds() {
            return modules.stream()
                    .filter(ModulePatch::enabled)
                    .map(ModulePatch::moduleId)
                    .toList();
        }
    }

    public record ModulePatch(
            String moduleId,
            boolean enabled,
            Map<String, String> settings
    ) {
        public ModulePatch {
            moduleId = normalizeId(moduleId);
            settings = Map.copyOf(new LinkedHashMap<>(settings));
        }
    }

    private static String normalizeId(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid preset or module id: " + id);
        }
        return normalized;
    }
}
