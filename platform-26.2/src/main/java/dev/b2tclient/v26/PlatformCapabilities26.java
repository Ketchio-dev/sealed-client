package dev.b2tclient.v26;

import dev.b2tclient.common.module.BuiltinModuleCatalog;
import dev.b2tclient.common.module.ModuleRegistry;

import java.util.Set;

public final class PlatformCapabilities26 {
    public static final int EXPECTED_SUPPORTED_COUNT = 90;
    public static final Set<String> SUPPORTED_IDS = Set.of(
            "watermark",
            "coordinates",
            "direction",
            "speed",
            "fps",
            "ping",
            "health",
            "armor",
            "totem_count",
            "durability_warning",
            "biome",
            "player_count",
            "inventory_space",
            "supplies",
            "effects",
            "radar",
            "session",
            "clock",
            "death_position",
            "array_list",
            "target_hud",
            "server_info",
            "full_bright",
            "clear_weather",
            "no_view_bob",
            "waypoints",
            "portal_coords",
            "auto_walk",
            "auto_sprint",
            "safe_walk",
            "auto_center",
            "hole_snap",
            "step",
            "no_fall",
            "fast_swim",
            "jesus",
            "elytra_swap",
            "elytra_control",
            "ground_speed",
            "no_slow",
            "no_rotate",
            "auto_eat",
            "auto_tool",
            "auto_disconnect",
            "auto_reconnect",
            "auto_respawn",
            "anti_afk",
            "auto_totem",
            "auto_weapon",
            "trigger_bot",
            "offhand",
            "anti_weakness",
            "criticals",
            "kill_aura",
            "auto_mine",
            "auto_crystal",
            "surround",
            "hole_fill",
            "self_trap",
            "auto_trap",
            "burrow",
            "anchor_aura",
            "bed_aura",
            "bow_aim",
            "quiver",
            "city_breaker",
            "piston_crystal",
            "player_esp",
            "tracers",
            "nametags",
            "storage_esp",
            "hole_esp",
            "block_esp",
            "trajectories",
            "freecam",
            "xray",
            "chams",
            "new_chunks",
            "logout_spots",
            "stash_finder",
            "auto_armor",
            "replenish",
            "chest_swap",
            "auto_mend",
            "fast_use",
            "inventory_manager",
            "auto_craft",
            "baritone_navigator",
            "tick_rate",
            "totem_pop_local"
    );

    private PlatformCapabilities26() {
    }

    public static ModuleRegistry createRegistry() {
        ModuleRegistry registry = new ModuleRegistry();
        BuiltinModuleCatalog.populate(
                registry,
                SUPPORTED_IDS,
                PlatformModuleSettings26::create
        );
        return registry;
    }
}
