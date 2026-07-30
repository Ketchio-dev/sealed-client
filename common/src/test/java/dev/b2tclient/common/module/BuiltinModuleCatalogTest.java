package dev.b2tclient.common.module;

import dev.b2tclient.common.setting.IntegerSetting;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinModuleCatalogTest {
    @Test
    void catalogContainsEveryCurrentRuntimeModuleExactlyOnce() {
        var entries = BuiltinModuleCatalog.entries();
        var ids = entries.stream().map(BuiltinModuleCatalog.CatalogEntry::id).toList();

        assertEquals(BuiltinModuleCatalog.EXPECTED_MODULE_COUNT, entries.size());
        assertEquals(entries.size(), new HashSet<>(ids).size());
        assertTrue(ids.containsAll(Set.of(
                "watermark", "coordinates", "auto_totem", "piston_crystal",
                "full_bright", "logout_spots", "auto_walk", "ground_speed",
                "array_list", "inventory_manager", "freecam", "xray", "chams",
                "stash_finder", "portal_coords", "auto_craft", "no_slow", "no_rotate"
        )));
    }

    @Test
    void unsupportedModulesAreFailClosed() {
        ModuleRegistry registry = new ModuleRegistry();
        BuiltinModuleCatalog.populate(registry, Set.of("watermark"));

        RegisteredModule supported = registry.find("watermark").orElseThrow();
        RegisteredModule unsupported = registry.find("kill_aura").orElseThrow();

        assertTrue(supported.descriptor().available());
        assertFalse(unsupported.descriptor().available());
        assertFalse(unsupported.toggle());
        assertThrows(IllegalStateException.class, () -> unsupported.setEnabled(true));
    }

    @Test
    void categoryAndRiskExceptionsMatchTheCurrentRuntime() {
        var entries = BuiltinModuleCatalog.entries().stream()
                .collect(java.util.stream.Collectors.toMap(BuiltinModuleCatalog.CatalogEntry::id, entry -> entry));

        assertEquals(ModuleCategory.UTILITY, entries.get("new_chunks").category());
        assertEquals(ModuleCategory.UTILITY, entries.get("logout_spots").category());
        assertEquals(ModuleCategory.UTILITY, entries.get("stash_finder").category());
        assertEquals(ModuleCategory.UTILITY, entries.get("portal_coords").category());
        assertEquals(ModuleRisk.MOVEMENT, entries.get("freecam").risk());
        assertEquals(ModuleRisk.PACKET, entries.get("no_rotate").risk());
        assertEquals(ModuleRisk.PACKET, entries.get("no_fall").risk());
        assertEquals(ModuleRisk.PACKET, entries.get("fast_use").risk());
        assertEquals(ModuleRisk.PACKET, entries.get("auto_reconnect").risk());
        assertEquals(ModuleRisk.AUTOMATION, entries.get("elytra_swap").risk());
    }

    @Test
    void platformSettingsAreAttachedOnlyToAvailableModules() {
        ModuleRegistry registry = new ModuleRegistry();
        AtomicInteger factoryCalls = new AtomicInteger();

        BuiltinModuleCatalog.populate(registry, Set.of("kill_aura"), entry -> {
            factoryCalls.incrementAndGet();
            return List.of(new IntegerSetting(
                    "range",
                    "Range",
                    "Platform-specific test setting.",
                    4,
                    1,
                    6,
                    1
            ));
        });

        assertEquals(1, factoryCalls.get());
        assertEquals(1, registry.find("kill_aura").orElseThrow().settings().size());
        assertTrue(registry.find("watermark").orElseThrow().settings().isEmpty());
        assertFalse(registry.find("watermark").orElseThrow().descriptor().available());
    }
}
