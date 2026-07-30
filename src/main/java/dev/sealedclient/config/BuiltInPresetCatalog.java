package dev.sealedclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stable, local-only preset catalog. Presets are deliberately partial: fields
 * not listed here remain owned by the user's active profile.
 */
public final class BuiltInPresetCatalog {
    public static final String LOW_LAG_UTILITY_ID = "low_lag_utility";
    public static final String TRAVEL_SAFE_ID = "travel_safe";
    public static final String CRYSTAL_PRACTICE_ID = "crystal_practice";

    private static final List<Preset> PRESETS = List.of(
            new Preset(
                    LOW_LAG_UTILITY_ID,
                    "Low-lag Utility",
                    "Keeps essential HUD information and disables costly world overlays.",
                    List.of(
                            enabled("fps", true),
                            enabled("ping", true),
                            enabled("tick_rate", true),
                            enabled("coordinates", true),
                            enabled("server_info", true),
                            enabled("array_list", true),
                            enabled("radar", false),
                            enabled("target_hud", false),
                            enabled("player_esp", false),
                            enabled("storage_esp", false),
                            enabled("tracers", false),
                            enabled("chams", false),
                            enabled("hole_esp", false),
                            enabled("block_esp", false),
                            enabled("xray", false),
                            enabled("freecam", false),
                            enabled("trajectories", false),
                            enabled("new_chunks", false),
                            enabled("stash_finder", false),
                            enabled("logout_spots", false)
                    )
            ),
            new Preset(
                    TRAVEL_SAFE_ID,
                    "Travel Safe",
                    "Travel HUD plus conservative edge, sprint, elytra, totem, and reconnect aids.",
                    List.of(
                            enabled("coordinates", true),
                            enabled("direction", true),
                            enabled("speed", true),
                            enabled("durability_warning", true),
                            enabled("death_position", true),
                            enabled("totem_count", true),
                            enabled("server_info", true),
                            enabled("waypoints", true),
                            enabled("portal_coords", true),
                            configured("auto_sprint", true, Map.of(
                                    "require_forward", json(true)
                            )),
                            enabled("safe_walk", true),
                            configured("elytra_swap", true, Map.of(
                                    "fall_distance", json(2.0),
                                    "minimum_durability", json(16),
                                    "restore_armor", json(true)
                            )),
                            configured("auto_totem", true, Map.of(
                                    "health", json(16.0),
                                    "replace_offhand", json(true),
                                    "delay", json(2)
                            )),
                            configured("auto_reconnect", true, Map.of(
                                    "delay_seconds", json(15),
                                    "maximum_attempts", json(5)
                            )),
                            enabled("ground_speed", false),
                            enabled("jesus", false),
                            enabled("fast_swim", false),
                            enabled("step", false),
                            enabled("no_slow", false),
                            enabled("freecam", false)
                    )
            ),
            new Preset(
                    CRYSTAL_PRACTICE_ID,
                    "Crystal Practice",
                    "Conservative crystal practice setup with friend and self-safety defaults.",
                    List.of(
                            enabled("health", true),
                            enabled("armor", true),
                            enabled("totem_count", true),
                            enabled("supplies", true),
                            enabled("target_hud", true),
                            enabled("totem_pop_local", true),
                            enabled("hole_esp", true),
                            enabled("nametags", true),
                            configured("auto_totem", true, Map.of(
                                    "health", json(20.0),
                                    "replace_offhand", json(true),
                                    "delay", json(2)
                            )),
                            configured("offhand", true, Map.of(
                                    "item", json("END_CRYSTAL"),
                                    "emergency_totem", json(true),
                                    "emergency_health", json(16.0),
                                    "replace", json(true),
                                    "delay", json(2)
                            )),
                            configured("auto_crystal", true, Map.of(
                                    "break", json(true),
                                    "place", json(true),
                                    "target_range", json(8.0),
                                    "break_range", json(4.5),
                                    "place_range", json(4.5),
                                    "min_self_distance", json(3.0),
                                    "friend_safety", json(5.0),
                                    "delay", json(3),
                                    "rotate", json(true)
                            )),
                            enabled("auto_weapon", true),
                            enabled("anti_weakness", true),
                            enabled("surround", true),
                            enabled("kill_aura", false),
                            enabled("bed_aura", false),
                            enabled("anchor_aura", false),
                            enabled("piston_crystal", false),
                            enabled("auto_trap", false),
                            enabled("hole_fill", false),
                            enabled("burrow", false)
                    )
            )
    );

    private BuiltInPresetCatalog() {
    }

    public static List<Preset> all() {
        return PRESETS;
    }

    public static Optional<Preset> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return PRESETS.stream().filter(preset -> preset.id().equals(normalized)).findFirst();
    }

    private static ModulePatch enabled(String moduleId, boolean enabled) {
        return configured(moduleId, enabled, Map.of());
    }

    private static ModulePatch configured(
            String moduleId,
            boolean enabled,
            Map<String, JsonElement> settings
    ) {
        return new ModulePatch(moduleId, enabled, settings);
    }

    private static JsonPrimitive json(boolean value) {
        return new JsonPrimitive(value);
    }

    private static JsonPrimitive json(int value) {
        return new JsonPrimitive(value);
    }

    private static JsonPrimitive json(double value) {
        return new JsonPrimitive(value);
    }

    private static JsonPrimitive json(String value) {
        return new JsonPrimitive(value);
    }

    public record Preset(
            String id,
            String name,
            String description,
            List<ModulePatch> modules
    ) {
        public Preset {
            id = requireId(id);
            modules = List.copyOf(modules);
        }
    }

    public record ModulePatch(
            String moduleId,
            boolean enabled,
            Map<String, JsonElement> settings
    ) {
        public ModulePatch {
            moduleId = requireId(moduleId);
            Map<String, JsonElement> copied = new LinkedHashMap<>();
            settings.forEach((id, value) -> copied.put(requireId(id), value.deepCopy()));
            settings = Map.copyOf(copied);
        }
    }

    private static String requireId(String id) {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid preset catalog id: " + id);
        }
        return id;
    }
}
