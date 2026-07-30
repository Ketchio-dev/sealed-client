package dev.b2tclient.common.module;

import dev.b2tclient.common.setting.Setting;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Version-neutral metadata for every module registered by the 1.21.4 runtime.
 * Platform adapters decide which entries have working hooks.
 */
public final class BuiltinModuleCatalog {
    public static final int EXPECTED_MODULE_COUNT = 90;
    public static final String UNAVAILABLE_DETAIL =
            "Catalog only on Minecraft 26.2; the version-specific game hook has not been ported";

    private static final List<CatalogEntry> ENTRIES = List.of(
            hud("watermark", "Watermark", true),
            hud("coordinates", "Coordinates", true),
            hud("direction", "Direction", true),
            hud("speed", "Speed", true),
            hud("fps", "FPS", true),
            hud("ping", "Ping", true),
            hud("health", "Health", true),
            hud("totem_count", "Totem Count", true),
            hud("armor", "Armor", true),
            hud("durability_warning", "Durability Warning", true),
            hud("biome", "Biome", false),
            hud("player_count", "Player Count", false),
            hud("inventory_space", "Inventory Space", false),
            hud("supplies", "Supplies", false),
            hud("effects", "Effects", false),
            hud("radar", "Player Radar", false),
            hud("session", "Session", false),
            hud("clock", "Clock", false),
            hud("death_position", "Death Position", true),

            automation("auto_totem", "Auto Totem", ModuleCategory.COMBAT),
            automation("auto_weapon", "Auto Weapon", ModuleCategory.COMBAT),
            automation("trigger_bot", "Trigger Bot", ModuleCategory.COMBAT),
            automation("offhand", "Offhand", ModuleCategory.COMBAT),
            automation("anti_weakness", "Anti Weakness", ModuleCategory.COMBAT),
            automation("criticals", "Criticals", ModuleCategory.COMBAT),
            automation("kill_aura", "Kill Aura", ModuleCategory.COMBAT),
            automation("surround", "Surround", ModuleCategory.COMBAT),
            automation("hole_fill", "Hole Fill", ModuleCategory.COMBAT),
            automation("auto_mine", "Auto Mine", ModuleCategory.COMBAT),
            automation("auto_crystal", "Auto Crystal", ModuleCategory.COMBAT),
            automation("self_trap", "Self Trap", ModuleCategory.COMBAT),
            automation("auto_trap", "Auto Trap", ModuleCategory.COMBAT),
            automation("burrow", "Burrow", ModuleCategory.COMBAT),
            automation("anchor_aura", "Anchor Aura", ModuleCategory.COMBAT),
            automation("bed_aura", "Bed Aura", ModuleCategory.COMBAT),
            automation("bow_aim", "Bow Aim", ModuleCategory.COMBAT),
            automation("quiver", "Quiver", ModuleCategory.COMBAT),
            automation("city_breaker", "City Breaker", ModuleCategory.COMBAT),
            automation("piston_crystal", "Piston Crystal", ModuleCategory.COMBAT),

            passive("clear_weather", "Clear Weather", ModuleCategory.VISUAL),
            passive("full_bright", "Full Bright", ModuleCategory.VISUAL),
            passive("no_view_bob", "No View Bob", ModuleCategory.VISUAL),
            passive("player_esp", "Player ESP", ModuleCategory.VISUAL),
            passive("tracers", "Tracers", ModuleCategory.VISUAL),
            passive("nametags", "Nametags", ModuleCategory.VISUAL),
            passive("storage_esp", "Storage ESP", ModuleCategory.VISUAL),
            passive("hole_esp", "Hole ESP", ModuleCategory.VISUAL),
            passive("block_esp", "Block ESP", ModuleCategory.VISUAL),
            passive("trajectories", "Trajectories", ModuleCategory.VISUAL),
            passive("waypoints", "Waypoints", ModuleCategory.VISUAL),
            new CatalogEntry(
                    "new_chunks",
                    "New Chunks",
                    "Marks chunks first observed in the current client session; "
                            + "this is not proof that the server generated them recently.",
                    ModuleCategory.UTILITY,
                    ModuleRisk.PASSIVE,
                    false
            ),
            passive("logout_spots", "Logout Spots", ModuleCategory.UTILITY),
            withRisk("freecam", "Freecam", ModuleCategory.VISUAL, ModuleRisk.MOVEMENT),
            passive("xray", "XRay", ModuleCategory.VISUAL),
            passive("chams", "Chams", ModuleCategory.VISUAL),
            passive("stash_finder", "Stash Finder", ModuleCategory.UTILITY),
            passive("portal_coords", "Portal Coords", ModuleCategory.UTILITY),

            automation("auto_walk", "Auto Walk", ModuleCategory.MOVEMENT),
            automation("auto_sprint", "Auto Sprint", ModuleCategory.MOVEMENT),
            automation("safe_walk", "Safe Walk", ModuleCategory.MOVEMENT),
            automation("auto_center", "Auto Center", ModuleCategory.MOVEMENT),
            automation("hole_snap", "Hole Snap", ModuleCategory.MOVEMENT),
            automation("step", "Step", ModuleCategory.MOVEMENT),
            withRisk("no_fall", "No Fall", ModuleCategory.MOVEMENT, ModuleRisk.PACKET),
            automation("fast_swim", "Fast Swim", ModuleCategory.MOVEMENT),
            automation("jesus", "Jesus", ModuleCategory.MOVEMENT),
            withRisk("elytra_swap", "Elytra Swap", ModuleCategory.MOVEMENT, ModuleRisk.AUTOMATION),
            automation("elytra_control", "Elytra Control", ModuleCategory.MOVEMENT),
            automation("ground_speed", "Ground Speed", ModuleCategory.MOVEMENT),
            automation("no_slow", "No Slow", ModuleCategory.MOVEMENT),
            withRisk("no_rotate", "No Rotate", ModuleCategory.MOVEMENT, ModuleRisk.PACKET),

            automation("auto_eat", "Auto Eat", ModuleCategory.UTILITY),
            automation("auto_disconnect", "Auto Disconnect", ModuleCategory.UTILITY),
            automation("auto_armor", "Auto Armor", ModuleCategory.UTILITY),
            automation("auto_tool", "Auto Tool", ModuleCategory.UTILITY),
            hud("array_list", "Array List", false),
            hud("tick_rate", "Tick Rate", false),
            hud("target_hud", "Target HUD", false),
            hud("server_info", "Server Info", false),
            hud("totem_pop_local", "Totem Pop (Local)", false),
            automation("replenish", "Replenish", ModuleCategory.UTILITY),
            automation("auto_respawn", "Auto Respawn", ModuleCategory.UTILITY),
            withRisk("auto_reconnect", "Auto Reconnect", ModuleCategory.UTILITY, ModuleRisk.PACKET),
            automation("anti_afk", "Anti AFK", ModuleCategory.UTILITY),
            automation("chest_swap", "Chest Swap", ModuleCategory.UTILITY),
            automation("auto_mend", "Auto Mend", ModuleCategory.UTILITY),
            withRisk("fast_use", "Fast Use", ModuleCategory.UTILITY, ModuleRisk.PACKET),
            automation("inventory_manager", "Inventory Manager", ModuleCategory.UTILITY),
            automation("auto_craft", "Auto Craft", ModuleCategory.UTILITY),
            withRisk(
                    "baritone_navigator",
                    "Baritone Navigator",
                    ModuleCategory.UTILITY,
                    ModuleRisk.AUTOMATION
            )
    );

    private BuiltinModuleCatalog() {
    }

    public static List<CatalogEntry> entries() {
        return ENTRIES;
    }

    public static void populate(ModuleRegistry registry, Set<String> supportedIds) {
        populate(registry, supportedIds, ignored -> List.of());
    }

    /**
     * Populates a version adapter while allowing it to attach platform-specific
     * settings only to modules that actually have working hooks.
     *
     * <p>The factory is deliberately not invoked for unavailable catalog
     * entries. This keeps unported modules fail-closed in configuration UIs and
     * prevents stale setting values from looking like working functionality.</p>
     */
    public static void populate(
            ModuleRegistry registry,
            Set<String> supportedIds,
            Function<CatalogEntry, List<Setting<?>>> settingsFactory
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(supportedIds, "supportedIds");
        Objects.requireNonNull(settingsFactory, "settingsFactory");
        for (CatalogEntry entry : ENTRIES) {
            boolean available = supportedIds.contains(entry.id());
            List<Setting<?>> settings = available
                    ? List.copyOf(Objects.requireNonNull(
                    settingsFactory.apply(entry),
                    "settingsFactory result for " + entry.id()
            ))
                    : List.of();
            registry.register(new ModuleDescriptor(
                    entry.id(),
                    entry.name(),
                    entry.description(),
                    entry.category(),
                    entry.risk(),
                    available && entry.enabledByDefault(),
                    available ? ModuleAvailability.AVAILABLE : ModuleAvailability.UNAVAILABLE,
                    available
                            ? capabilityDetail(entry)
                            : UNAVAILABLE_DETAIL
            ), settings.toArray(Setting<?>[]::new));
        }
    }

    private static String capabilityDetail(CatalogEntry entry) {
        if ("baritone_navigator".equals(entry.id())) {
            return "Implemented on Minecraft 26.2; requires a separately "
                    + "installed compatible Baritone provider";
        }
        return "Implemented and active on Minecraft 26.2";
    }

    private static CatalogEntry hud(String id, String name, boolean enabledByDefault) {
        return new CatalogEntry(
                id,
                name,
                "Displays " + name.toLowerCase() + " information in the client HUD.",
                ModuleCategory.HUD,
                ModuleRisk.PASSIVE,
                enabledByDefault
        );
    }

    private static CatalogEntry passive(String id, String name, ModuleCategory category) {
        return new CatalogEntry(
                id,
                name,
                "Client-side " + name.toLowerCase() + " feature.",
                category,
                ModuleRisk.PASSIVE,
                false
        );
    }

    private static CatalogEntry automation(String id, String name, ModuleCategory category) {
        return new CatalogEntry(
                id,
                name,
                "Automates the " + name.toLowerCase() + " behavior.",
                category,
                category == ModuleCategory.COMBAT
                        ? ModuleRisk.COMBAT
                        : category == ModuleCategory.MOVEMENT ? ModuleRisk.MOVEMENT : ModuleRisk.AUTOMATION,
                false
        );
    }

    private static CatalogEntry withRisk(
            String id,
            String name,
            ModuleCategory category,
            ModuleRisk risk
    ) {
        return new CatalogEntry(
                id,
                name,
                "Client-side " + name.toLowerCase() + " feature.",
                category,
                risk,
                false
        );
    }

    public record CatalogEntry(
            String id,
            String name,
            String description,
            ModuleCategory category,
            ModuleRisk risk,
            boolean enabledByDefault
    ) {
    }
}
