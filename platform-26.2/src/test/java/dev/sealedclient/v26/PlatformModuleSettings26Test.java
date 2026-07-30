package dev.sealedclient.v26;

import dev.sealedclient.common.module.BuiltinModuleCatalog;
import dev.sealedclient.common.module.ModuleRegistry;
import dev.sealedclient.common.setting.Setting;
import dev.sealedclient.v26.combat.CombatInventoryAutomation26;
import dev.sealedclient.v26.combat.CombatSiegeAutomation26;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformModuleSettings26Test {
    @Test
    void disconnectCleanupConsumesBaritoneConfirmationAndDisablesModule() {
        ModuleRegistry registry = new ModuleRegistry();
        BuiltinModuleCatalog.populate(
                registry,
                Set.of("baritone_navigator"),
                PlatformModuleSettings26::create
        );
        var module = registry.find("baritone_navigator").orElseThrow();
        module.setEnabled(true);
        set(registry, "baritone_navigator", "confirm_target", "true");

        PlatformModuleSettings26.clearBaritoneSessionControls(registry);

        assertFalse(module.enabled());
        assertEquals(
                "false",
                setting(
                        registry,
                        "baritone_navigator",
                        "confirm_target"
                ).serialize()
        );
    }

    @Test
    void combatSettingsAreLiveBoundedAndConvertedToRuntimeConfiguration() {
        ModuleRegistry registry = registry();
        set(registry, "auto_totem", "health", "13.5");
        set(registry, "auto_totem", "delay", "7");
        set(registry, "offhand", "item", "shield");
        set(registry, "offhand", "delay", "5");
        set(registry, "auto_weapon", "minimum_durability", "9");

        var inventory =
                PlatformModuleSettings26.inventoryConfiguration(registry, true);
        assertEquals(13.5F, inventory.autoTotemHealth());
        assertEquals(7, inventory.autoTotemCooldownTicks());
        assertEquals(5, inventory.offhandCooldownTicks());
        assertEquals(9, inventory.minimumWeaponDurability());
        assertEquals(
                CombatInventoryAutomation26.OffhandItem.SHIELD,
                inventory.preferredOffhand()
        );

        set(registry, "kill_aura", "range", "99");
        set(registry, "kill_aura", "cooldown", "0.73");
        var attack =
                PlatformModuleSettings26.attackConfiguration(registry, false);
        assertEquals(3.0, attack.range(), 1.0E-9);
        assertEquals(0.73F, attack.cooldownThreshold(), 1.0E-6F);

        set(registry, "auto_crystal", "maximum_self_damage", "9.5");
        set(registry, "auto_mine", "minimum_health", "11");
        var crystal =
                PlatformModuleSettings26.crystalMineConfiguration(registry);
        assertEquals(9.5, crystal.maximumSelfDamage(), 1.0E-9);
        assertEquals(11.0, crystal.minimumMineHealth(), 1.0E-9);
    }

    @Test
    void expandedCombatConfigurationsKeepEveryModulePolicyIndependent() {
        ModuleRegistry registry = registry();
        set(registry, "surround", "placement_range", "5.5");
        set(registry, "surround", "minimum_health", "10");
        set(registry, "hole_fill", "placement_range", "4");
        set(registry, "hole_fill", "minimum_health", "12");
        set(registry, "hole_fill", "target_range", "8");
        set(registry, "auto_trap", "target_range", "4");
        set(registry, "burrow", "minimum_health", "15");

        var construction =
                PlatformModuleSettings26.defensiveConstructionConfiguration(
                        registry,
                        true,
                        true,
                        false,
                        true,
                        true
                );
        assertEquals(5.5, construction.surround().placementRange(), 1.0E-9);
        assertEquals(10.0, construction.surround().minimumHealth(), 1.0E-9);
        assertEquals(8.0, construction.holeFill().targetRange(), 1.0E-9);
        assertEquals(4.0, construction.holeFill().placementRange(), 1.0E-9);
        assertEquals(12.0, construction.holeFill().minimumHealth(), 1.0E-9);
        assertEquals(4.0, construction.autoTrap().targetRange(), 1.0E-9);
        assertEquals(15.0, construction.burrow().minimumHealth(), 1.0E-9);

        set(registry, "anchor_aura", "target_range", "12");
        set(registry, "bed_aura", "target_range", "6");
        set(registry, "anchor_aura", "minimum_damage", "4");
        set(registry, "bed_aura", "minimum_damage", "8");
        set(registry, "anchor_aura", "maximum_self_damage", "10");
        set(registry, "bed_aura", "maximum_self_damage", "9");
        var anchor = PlatformModuleSettings26.anchorConfiguration(registry);
        var bed = PlatformModuleSettings26.bedConfiguration(registry);
        assertEquals(12.0, anchor.targetRange(), 1.0E-9);
        assertEquals(4.0, anchor.minimumTargetDamage(), 1.0E-9);
        assertEquals(10.0, anchor.maximumSelfDamage(), 1.0E-9);
        assertEquals(6.0, bed.targetRange(), 1.0E-9);
        assertEquals(8.0, bed.minimumTargetDamage(), 1.0E-9);
        assertEquals(9.0, bed.maximumSelfDamage(), 1.0E-9);

        set(registry, "bow_aim", "fov", "45");
        set(registry, "bow_aim", "lead_ticks", "30");
        assertEquals(
                45.0,
                PlatformModuleSettings26.bowAimConfiguration(registry)
                        .fovDegrees(),
                1.0E-9
        );
        assertEquals(
                30.0,
                PlatformModuleSettings26.bowAimConfiguration(registry)
                        .maximumLeadTicks(),
                1.0E-9
        );

        set(registry, "quiver", "draw_ticks", "25");
        set(registry, "quiver", "minimum_health", "18");
        var quiver = PlatformModuleSettings26.quiverConfiguration(registry);
        assertEquals(25, quiver.drawTicks());
        assertEquals(18.0F, quiver.minimumHealth());

        set(registry, "city_breaker", "target_range", "8");
        set(registry, "city_breaker", "minimum_durability", "12");
        set(registry, "piston_crystal", "target_range", "6");
        set(registry, "piston_crystal", "minimum_damage", "9");
        set(registry, "piston_crystal", "cleanup_minimum_durability", "17");
        var city = PlatformModuleSettings26
                .cityBreakerConfiguration(registry);
        var piston = PlatformModuleSettings26
                .pistonCrystalConfiguration(registry);
        assertEquals(8.0, city.targetRange(), 1.0E-9);
        assertEquals(12, city.minimumToolDurability());
        assertEquals(6.0, piston.targetRange(), 1.0E-9);
        assertEquals(9.0, piston.minimumTargetDamage(), 1.0E-9);
        assertEquals(17, piston.cleanupMinimumToolDurability());

        CombatSiegeAutomation26 service =
                new CombatSiegeAutomation26();
        service.setModeConfiguration(
                new CombatSiegeAutomation26.ModeConfiguration(
                        city,
                        piston
                )
        );
        assertEquals(8.0, service.cityConfiguration().targetRange(), 1.0E-9);
        assertEquals(6.0, service.pistonConfiguration().targetRange(), 1.0E-9);
        assertEquals(
                17,
                service.pistonConfiguration()
                        .cleanupMinimumToolDurability()
        );

        set(registry, "city_breaker", "target_range", "2.0");
        set(registry, "piston_crystal", "target_range", "2.0");
        var minimumCity = assertDoesNotThrow(
                () -> PlatformModuleSettings26
                        .cityBreakerConfiguration(registry)
        );
        var minimumPiston = assertDoesNotThrow(
                () -> PlatformModuleSettings26
                        .pistonCrystalConfiguration(registry)
        );
        assertEquals(3.0, minimumCity.targetRange(), 1.0E-9);
        assertEquals(3.0, minimumPiston.targetRange(), 1.0E-9);
    }

    @Test
    void invalidChoiceIsRejectedAndUnavailableModulesExposeNoSettings() {
        ModuleRegistry registry = registry();

        assertThrows(
                IllegalArgumentException.class,
                () -> set(registry, "offhand", "item", "command_block")
        );
        assertTrue(
                registry.find("player_esp").orElseThrow().settings().isEmpty()
        );
    }

    @Test
    void movementSettingsAreLiveBoundedAndConvertedSafely() {
        ModuleRegistry registry = registry();
        set(registry, "safe_walk", "look_ahead", "0.7");
        set(registry, "auto_center", "speed", "0.18");
        set(registry, "hole_snap", "radius", "5");
        set(registry, "step", "height", "1.4");
        var walk =
                PlatformModuleSettings26.walkMovementConfiguration(registry);
        assertEquals(0.7, walk.safeWalkLookAhead(), 1.0E-9);
        assertEquals(0.18, walk.autoCenterSpeed(), 1.0E-9);
        assertEquals(5, walk.holeRadius());
        assertEquals(1.4, walk.stepHeight(), 1.0E-9);

        set(registry, "no_fall", "trigger_distance", "6.5");
        set(registry, "fast_swim", "speed", "0.31");
        set(registry, "jesus", "buoyancy", "0.1");
        var water = PlatformModuleSettings26
                .fallWaterMovementConfiguration(registry);
        assertEquals(6.5, water.noFallTriggerDistance(), 1.0E-9);
        assertEquals(0.31, water.fastSwimSpeed(), 1.0E-9);
        assertEquals(0.1, water.jesusBuoyancy(), 1.0E-9);

        set(registry, "elytra_swap", "fall_distance", "3.0");
        set(registry, "elytra_swap", "minimum_durability", "20");
        set(registry, "elytra_swap", "restore_armor", "false");
        var swap =
                PlatformModuleSettings26.elytraSwapConfiguration(registry);
        assertEquals(3.0, swap.fallDistance(), 1.0E-9);
        assertEquals(20, swap.minimumDurability());
        assertEquals(false, swap.restoreArmor());

        set(registry, "elytra_control", "cruise_speed", "1.7");
        set(registry, "ground_speed", "speed", "0.4");
        set(registry, "ground_speed", "acceleration", "0.1");
        var control =
                PlatformModuleSettings26.elytraControlConfiguration(registry);
        assertEquals(1.7, control.cruiseSpeed(), 1.0E-9);
        var input =
                PlatformModuleSettings26.movementInputConfiguration(registry);
        assertEquals(0.4, input.groundSpeed().targetSpeed(), 1.0E-9);
        assertEquals(
                0.1,
                input.groundSpeed().accelerationPerTick(),
                1.0E-9
        );

        set(registry, "no_rotate", "preserve_yaw", "false");
        set(registry, "no_rotate", "preserve_pitch", "false");
        var noOp =
                PlatformModuleSettings26.movementInputConfiguration(registry);
        assertFalse(noOp.noRotate().preserveYaw());
        assertFalse(noOp.noRotate().preservePitch());
    }

    @Test
    void remainingFeatureSettingsArePersistableBoundedAndValidated() {
        Set<String> expandedIds = Set.of(
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
        ModuleRegistry expanded = new ModuleRegistry();
        BuiltinModuleCatalog.populate(
                expanded,
                expandedIds,
                PlatformModuleSettings26::create
        );

        for (String id : expandedIds) {
            assertTrue(
                    expanded.find(id).orElseThrow().descriptor().available(),
                    id
            );
        }
        for (String id : expandedIds.stream()
                .filter(candidate -> !"tick_rate".equals(candidate))
                .filter(candidate -> !"totem_pop_local".equals(candidate))
                .toList()) {
            assertFalse(
                    expanded.find(id).orElseThrow().settings().isEmpty(),
                    id
            );
        }

        set(expanded, "player_esp", "player_color", "8044AAFF");
        assertEquals(
                "8044AAFF",
                setting(expanded, "player_esp", "player_color").serialize()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> set(expanded, "player_esp", "player_color", "red")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> set(
                        expanded,
                        "block_esp",
                        "targets",
                        "minecraft:diamond_ore\nminecraft:ancient_debris"
                )
        );

        set(expanded, "block_esp", "scan_budget", "999999");
        assertEquals(
                "16384",
                setting(expanded, "block_esp", "scan_budget").serialize()
        );
        set(expanded, "baritone_navigator", "target_x", "-40000000");
        assertEquals(
                "-30000000",
                setting(
                        expanded,
                        "baritone_navigator",
                        "target_x"
                ).serialize()
        );
    }

    @Test
    void remainingConfigurationConvertersAreTotalAcrossLegalSettings() {
        Set<String> supported = Set.of(
                "chams",
                "new_chunks",
                "logout_spots",
                "stash_finder",
                "auto_mend",
                "auto_craft"
        );
        ModuleRegistry registry = new ModuleRegistry();
        BuiltinModuleCatalog.populate(
                registry,
                supported,
                PlatformModuleSettings26::create
        );

        set(registry, "auto_mend", "start_at", "95");
        set(registry, "auto_mend", "stop_at", "10");
        var mend = assertDoesNotThrow(
                () -> PlatformModuleSettings26.autoMendConfiguration(registry)
        );
        assertEquals(95, mend.startAtPercent());
        assertEquals(95, mend.stopAtPercent());

        set(registry, "auto_craft", "delay", "100");
        var craft = assertDoesNotThrow(
                () -> PlatformModuleSettings26.autoCraftConfiguration(registry)
        );
        assertEquals(100, craft.actionDelayTicks());
        assertEquals(100, craft.confirmationTimeoutTicks());

        assertThrows(
                IllegalArgumentException.class,
                () -> set(registry, "chams", "color", "00FF5555")
        );

        ModuleRegistry blockRegistry = new ModuleRegistry();
        BuiltinModuleCatalog.populate(
                blockRegistry,
                Set.of("block_esp"),
                PlatformModuleSettings26::create
        );
        set(blockRegistry, "block_esp", "targets", "x".repeat(129));
        var blockVisuals = assertDoesNotThrow(
                () -> PlatformModuleSettings26.visualOverlayConfiguration(
                        blockRegistry
                )
        );
        assertTrue(blockVisuals.blockEsp().targets().isEmpty());

        set(registry, "new_chunks", "scan_interval", "40");
        set(registry, "logout_spots", "maximum_entries", "1024");
        set(registry, "stash_finder", "scan_budget", "2048");
        var trackers = assertDoesNotThrow(
                () -> PlatformModuleSettings26.worldTrackerConfiguration(
                        registry
                )
        );
        assertEquals(40, trackers.newChunksScanIntervalTicks());
        assertEquals(
                1_024,
                trackers.logoutSpots().maximumEntries()
        );
        assertEquals(2_048, trackers.stashFinder().operationBudget());
        var trackerRender = assertDoesNotThrow(
                () -> PlatformModuleSettings26.worldTrackerRenderConfiguration(
                        registry
                )
        );
        assertEquals(0x9900D7FF, trackerRender.newChunks().argb());
        assertEquals(1.5F, trackerRender.newChunks().lineWidth());
    }

    private static ModuleRegistry registry() {
        ModuleRegistry registry = new ModuleRegistry();
        BuiltinModuleCatalog.populate(
                registry,
                Set.of(
                        "auto_totem",
                        "offhand",
                        "auto_weapon",
                        "trigger_bot",
                        "kill_aura",
                        "auto_crystal",
                        "auto_mine",
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
                ),
                PlatformModuleSettings26::create
        );
        return registry;
    }

    private static void set(
            ModuleRegistry registry,
            String module,
            String setting,
            String value
    ) {
        setting(registry, module, setting).deserialize(value);
    }

    private static Setting<?> setting(
            ModuleRegistry registry,
            String module,
            String setting
    ) {
        return registry.find(module).orElseThrow().settings()
                .stream()
                .filter(candidate -> candidate.id().equals(setting))
                .findFirst()
                .orElseThrow();
    }
}
