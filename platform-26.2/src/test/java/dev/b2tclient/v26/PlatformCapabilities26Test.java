package dev.b2tclient.v26;

import dev.b2tclient.common.module.BuiltinModuleCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformCapabilities26Test {
    @Test
    void fullCatalogLoadsWithExplicitCapabilityState() {
        var registry = PlatformCapabilities26.createRegistry();

        assertEquals(BuiltinModuleCatalog.EXPECTED_MODULE_COUNT, registry.all().size());
        assertEquals(PlatformCapabilities26.EXPECTED_SUPPORTED_COUNT, PlatformCapabilities26.SUPPORTED_IDS.size());
        assertEquals(
                PlatformCapabilities26.SUPPORTED_IDS.size(),
                registry.all().stream().filter(module -> module.descriptor().available()).count()
        );
        for (String id : java.util.Set.of(
                "ping",
                "totem_count",
                "durability_warning",
                "supplies",
                "radar",
                "death_position",
                "clear_weather",
                "auto_eat",
                "auto_tool",
                "auto_reconnect",
                "auto_respawn",
                "anti_afk",
                "waypoints",
                "portal_coords",
                "array_list",
                "target_hud",
                "server_info",
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
                "no_rotate"
        )) {
            assertTrue(registry.find(id).orElseThrow().descriptor().available(), id);
        }
        for (String id : java.util.Set.of(
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
                "piston_crystal"
        )) {
            assertTrue(registry.find(id).orElseThrow().descriptor().available(), id);
        }
        assertEquals(
                BuiltinModuleCatalog.EXPECTED_MODULE_COUNT - PlatformCapabilities26.EXPECTED_SUPPORTED_COUNT,
                registry.all().stream().filter(module -> !module.descriptor().available()).count()
        );
        assertTrue(registry.find("player_esp").orElseThrow().descriptor().available());
        assertFalse(registry.find("player_esp").orElseThrow().enabled());
    }
}
