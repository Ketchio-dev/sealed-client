package dev.sealedclient.v26;

import dev.sealedclient.common.module.BuiltinModuleCatalog;
import dev.sealedclient.common.module.ModuleRegistry;
import dev.sealedclient.common.module.RegisteredModule;
import dev.sealedclient.common.setting.BooleanSetting;
import dev.sealedclient.common.setting.DoubleSetting;
import dev.sealedclient.common.setting.IntegerSetting;
import dev.sealedclient.common.setting.Setting;
import dev.sealedclient.common.setting.StringSetting;
import dev.sealedclient.v26.combat.CombatAttackAutomation26;
import dev.sealedclient.v26.combat.CombatBedAnchorAutomation26;
import dev.sealedclient.v26.combat.CombatBowAimAutomation26;
import dev.sealedclient.v26.combat.CombatCrystalMineAutomation26;
import dev.sealedclient.v26.combat.CombatDefensiveConstructionAutomation26;
import dev.sealedclient.v26.combat.CombatInventoryAutomation26;
import dev.sealedclient.v26.combat.CombatQuiverAutomation26;
import dev.sealedclient.v26.combat.CombatSiegeAutomation26;
import dev.sealedclient.v26.movement.ElytraControlAutomation26;
import dev.sealedclient.v26.movement.ElytraSwapAutomation26;
import dev.sealedclient.v26.movement.FallWaterMovementAutomation26;
import dev.sealedclient.v26.movement.MovementInputAutomation26;
import dev.sealedclient.v26.movement.NoRotatePolicy26;
import dev.sealedclient.v26.movement.WalkMovementAutomation26;
import dev.sealedclient.v26.utility.AutoArmorAutomation26;
import dev.sealedclient.v26.utility.AutoCraftAutomation26;
import dev.sealedclient.v26.utility.AutoMendAutomation26;
import dev.sealedclient.v26.utility.AutoMendDecisionEngine26;
import dev.sealedclient.v26.utility.ChestSwapAutomation26;
import dev.sealedclient.v26.utility.FastUseAutomation26;
import dev.sealedclient.v26.utility.FastUseDecisionEngine26;
import dev.sealedclient.v26.utility.InventoryManagerAutomation26;
import dev.sealedclient.v26.utility.ReplenishAutomation26;
import dev.sealedclient.v26.visual.ChamsController26;
import dev.sealedclient.v26.visual.FreecamController26;
import dev.sealedclient.v26.visual.VisualOverlayConfiguration26;
import dev.sealedclient.v26.visual.XRayController26;
import dev.sealedclient.v26.world.LogoutSpotsDecisionEngine26;
import dev.sealedclient.v26.world.NewChunksDecisionEngine26;
import dev.sealedclient.v26.world.StashFinderDecisionEngine26;
import dev.sealedclient.v26.world.WorldTrackerRenderService26;
import dev.sealedclient.v26.world.WorldTrackerService26;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Live, persisted settings owned by the Minecraft 26.2 platform adapter.
 */
final class PlatformModuleSettings26 {
    private PlatformModuleSettings26() {
    }

    static List<Setting<?>> create(
            BuiltinModuleCatalog.CatalogEntry entry
    ) {
        return switch (entry.id()) {
            case "auto_totem" -> List.of(
                    decimal(
                            "health",
                            "Health",
                            "Equip a totem at or below this health plus absorption.",
                            20.0,
                            1.0,
                            40.0,
                            0.5
                    ),
                    bool(
                            "replace_offhand",
                            "Replace offhand",
                            "Allow an emergency totem to replace the held offhand item.",
                            true
                    ),
                    integer(
                            "delay",
                            "Delay",
                            "Ticks between inventory swaps.",
                            3,
                            1,
                            20
                    )
            );
            case "offhand" -> List.of(
                    new StringSetting(
                            "item",
                            "Item",
                            "END_CRYSTAL, TOTEM, ENCHANTED_GOLDEN_APPLE, or SHIELD.",
                            "END_CRYSTAL",
                            24,
                            PlatformModuleSettings26::validOffhand,
                            () -> true
                    ),
                    bool(
                            "emergency_totem",
                            "Emergency totem",
                            "Use a totem instead of the preferred item at low health.",
                            true
                    ),
                    decimal(
                            "emergency_health",
                            "Emergency health",
                            "Health plus absorption at which a totem takes priority.",
                            16.0,
                            1.0,
                            40.0,
                            0.5
                    ),
                    bool(
                            "replace",
                            "Replace",
                            "Replace a different item already held in the offhand.",
                            true
                    ),
                    integer(
                            "delay",
                            "Delay",
                            "Ticks between inventory swaps.",
                            3,
                            1,
                            20
                    )
            );
            case "auto_weapon" -> List.of(integer(
                    "minimum_durability",
                    "Minimum durability",
                    "Do not select a weapon at or below this remaining durability.",
                    3,
                    0,
                    100
            ));
            case "trigger_bot", "kill_aura" -> List.of(
                    decimal(
                            "range",
                            "Range",
                            "Maximum server-compatible entity attack range.",
                            3.0,
                            2.0,
                            3.0,
                            0.1
                    ),
                    decimal(
                            "cooldown",
                            "Cooldown",
                            "Required vanilla attack-strength scale.",
                            0.92,
                            0.50,
                            1.00,
                            0.01
                    ),
                    integer(
                            "minimum_ticks",
                            "Minimum ticks",
                            "Minimum client ticks between attacks.",
                            1,
                            1,
                            20
                    )
            );
            case "auto_crystal" -> List.of(
                    decimal("target_range", "Target range", "Maximum enemy selection range.", 10.0, 3.0, 16.0, 0.5),
                    decimal("break_range", "Break range", "Maximum crystal attack range.", 4.5, 2.0, 6.0, 0.1),
                    decimal("place_range", "Place range", "Maximum crystal placement range.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_damage", "Minimum damage", "Minimum estimated target damage.", 5.5, 0.0, 36.0, 0.5),
                    decimal("maximum_self_damage", "Maximum self damage", "Maximum estimated local-player damage.", 12.0, 0.0, 36.0, 0.5),
                    decimal("maximum_friend_damage", "Maximum friend damage", "Maximum estimated damage to a friend.", 4.0, 0.0, 36.0, 0.5),
                    decimal("self_reserve", "Self reserve", "Health reserve after estimated self damage.", 6.0, 0.0, 20.0, 0.5),
                    decimal("friend_reserve", "Friend reserve", "Minimum friend health reserve.", 6.0, 0.0, 20.0, 0.5),
                    decimal("minimum_health", "Minimum health", "Do not crystal below this health plus absorption.", 12.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks between confirmed crystal actions.", 2, 0, 20),
                    integer("failure_delay", "Failure delay", "Cooldown ticks after a failed confirmation.", 40, 1, 200)
            );
            case "auto_mine" -> List.of(
                    decimal("range", "Range", "Maximum mining range.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_health", "Minimum health", "Stop mining below this health plus absorption.", 8.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks before selecting another mining target.", 3, 0, 20)
            );
            case "surround" -> List.of(
                    bool("floor", "Floor", "Also protect the block directly below the player.", true),
                    decimal("placement_range", "Placement range", "Maximum block interaction distance.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_health", "Minimum health", "Stop building below this health plus absorption.", 8.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks between confirmed placements.", 2, 0, 20),
                    integer("failure_delay", "Failure delay", "Cooldown after an unconfirmed placement.", 40, 1, 200)
            );
            case "hole_fill" -> List.of(
                    decimal("target_range", "Target range", "Maximum enemy selection range.", 8.0, 2.0, 16.0, 0.5),
                    decimal("placement_range", "Placement range", "Maximum block interaction distance.", 4.5, 2.0, 6.0, 0.1),
                    integer("scan_radius", "Scan radius", "Horizontal hole scan radius.", 4, 1, 6),
                    decimal("enemy_radius", "Enemy radius", "Only fill holes this close to the target.", 3.0, 1.0, 6.0, 0.5),
                    decimal("minimum_health", "Minimum health", "Stop building below this health plus absorption.", 8.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks between confirmed placements.", 2, 0, 20),
                    integer("failure_delay", "Failure delay", "Cooldown after an unconfirmed placement.", 40, 1, 200)
            );
            case "self_trap" -> List.of(
                    bool("head_sides", "Head sides", "Protect the sides at head height before closing the roof.", false),
                    decimal("placement_range", "Placement range", "Maximum block interaction distance.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_health", "Minimum health", "Stop building below this health plus absorption.", 8.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks between confirmed placements.", 2, 0, 20),
                    integer("failure_delay", "Failure delay", "Cooldown after an unconfirmed placement.", 40, 1, 200)
            );
            case "auto_trap" -> List.of(
                    decimal("target_range", "Target range", "Maximum enemy selection range.", 4.5, 2.0, 8.0, 0.1),
                    bool("head_sides", "Head sides", "Add blocks beside the target's head.", false),
                    decimal("placement_range", "Placement range", "Maximum block interaction distance.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_health", "Minimum health", "Stop building below this health plus absorption.", 8.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks between confirmed placements.", 2, 0, 20),
                    integer("failure_delay", "Failure delay", "Cooldown after an unconfirmed placement.", 40, 1, 200)
            );
            case "burrow" -> List.of(
                    bool("auto_jump", "Auto jump", "Use a normal jump before placing at the starting feet block.", true),
                    decimal("placement_range", "Placement range", "Maximum block interaction distance.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_health", "Minimum health", "Do not begin a burrow below this health plus absorption.", 12.0, 1.0, 40.0, 0.5),
                    decimal("minimum_rise", "Minimum rise", "Required vertical rise before the placement attempt.", 1.0, 0.6, 1.4, 0.1),
                    integer("timeout", "Timeout", "Maximum ticks for one conservative burrow attempt.", 16, 4, 40),
                    integer("failure_delay", "Failure delay", "Cooldown after a failed attempt.", 40, 1, 200)
            );
            case "anchor_aura", "bed_aura" -> List.of(
                    decimal("target_range", "Target range", "Maximum enemy selection range.", 10.0, 3.0, 16.0, 0.1),
                    decimal("use_range", "Use range", "Maximum detonation interaction distance.", 4.5, 2.0, 6.0, 0.1),
                    decimal("place_range", "Place range", "Maximum placement interaction distance.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_damage", "Minimum damage", "Minimum estimated target explosion damage.", 5.0, 0.0, 36.0, 0.5),
                    decimal("maximum_self_damage", "Maximum self damage", "Maximum estimated local-player damage.", 12.0, 0.0, 36.0, 0.5),
                    decimal("maximum_friend_damage", "Maximum friend damage", "Maximum estimated damage to a friend.", 4.0, 0.0, 36.0, 0.5),
                    decimal("self_reserve", "Self reserve", "Health reserve after estimated self damage.", 6.0, 0.0, 20.0, 0.5),
                    decimal("friend_reserve", "Friend reserve", "Minimum friend health reserve.", 6.0, 0.0, 20.0, 0.5),
                    decimal("minimum_health", "Minimum health", "Do not act below this health plus absorption.", 12.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks between confirmed actions.", 2, 0, 20),
                    integer("failure_delay", "Failure delay", "Cooldown after an unconfirmed action.", 40, 1, 200)
            );
            case "bow_aim" -> List.of(
                    decimal("range", "Range", "Maximum target range.", 48.0, 8.0, 96.0, 1.0),
                    decimal("bow_speed", "Bow speed", "Estimated fully-drawn bow projectile speed.", 3.0, 1.0, 4.0, 0.05),
                    decimal("crossbow_speed", "Crossbow speed", "Estimated crossbow projectile speed.", 3.15, 1.0, 5.0, 0.05),
                    decimal("gravity", "Gravity", "Estimated projectile gravity per tick.", 0.05, 0.0, 0.15, 0.005),
                    integer("lead_ticks", "Lead ticks", "Maximum target motion prediction ticks.", 40, 1, 80),
                    decimal("fov", "FOV", "Maximum angular target field.", 70.0, 5.0, 180.0, 1.0),
                    decimal("rotation_speed", "Rotation speed", "Maximum degrees rotated per tick.", 12.0, 1.0, 90.0, 1.0),
                    integer("minimum_draw", "Minimum draw", "Minimum bow draw ticks before aiming.", 5, 1, 20),
                    decimal("manual_override", "Manual override", "Mouse rotation delta that temporarily suppresses aiming.", 0.75, 0.1, 30.0, 0.05),
                    integer("override_ticks", "Override ticks", "Suppression ticks after manual mouse movement.", 5, 0, 40)
            );
            case "quiver" -> List.of(
                    integer("draw_ticks", "Draw ticks", "Bow draw duration before release.", 20, 5, 30),
                    decimal("minimum_health", "Minimum health", "Do not fire below this health plus absorption.", 16.0, 1.0, 40.0, 0.5),
                    integer("minimum_durability", "Minimum durability", "Required remaining bow durability.", 2, 2, 384),
                    integer("minimum_effect_ticks", "Minimum effect ticks", "Skip an arrow while its effect has enough time remaining.", 100, 0, 1200),
                    integer("confirmation_ticks", "Confirmation ticks", "Maximum ticks to observe ammunition or effect confirmation.", 100, 20, 200),
                    integer("shot_acceptance_ticks", "Shot acceptance", "Ticks allowed for the server to accept the released shot.", 8, 1, 20),
                    integer("success_delay", "Success delay", "Cooldown after a confirmed shot.", 100, 20, 400),
                    integer("failure_delay", "Failure delay", "Cooldown after a failed shot.", 40, 1, 200)
            );
            case "city_breaker" -> List.of(
                    decimal("target_range", "Target range", "Maximum enemy selection range.", 6.0, 3.0, 10.0, 0.1),
                    decimal("mine_range", "Mine range", "Maximum surround-block mining range.", 4.5, 2.0, 6.0, 0.1),
                    decimal("minimum_health", "Minimum health", "Stop mining below this health plus absorption.", 8.0, 1.0, 40.0, 0.5),
                    integer("minimum_durability", "Minimum durability", "Required remaining tool durability.", 5, 0, 1000),
                    integer("confirmation_ticks", "Confirmation ticks", "Maximum ticks to wait for a block-state change.", 240, 20, 400),
                    integer("maximum_retries", "Maximum retries", "Maximum bounded destroy restart attempts.", 1, 0, 3),
                    integer("action_delay", "Action delay", "Cooldown after a confirmed city break.", 4, 0, 40),
                    integer("failure_delay", "Failure delay", "Cooldown after an unconfirmed mining attempt.", 40, 1, 200)
            );
            case "piston_crystal" -> List.of(
                    decimal("target_range", "Target range", "Maximum enemy selection range.", 6.0, 3.0, 10.0, 0.1),
                    decimal("place_range", "Place range", "Maximum setup placement range.", 4.5, 2.0, 6.0, 0.1),
                    decimal("break_range", "Break range", "Maximum crystal attack range.", 5.0, 2.0, 6.0, 0.1),
                    decimal("minimum_damage", "Minimum damage", "Minimum estimated target crystal damage.", 6.0, 0.0, 36.0, 0.5),
                    decimal("maximum_self_damage", "Maximum self damage", "Maximum estimated local-player damage.", 12.0, 0.0, 36.0, 0.5),
                    decimal("maximum_friend_damage", "Maximum friend damage", "Maximum estimated friend damage.", 4.0, 0.0, 36.0, 0.5),
                    decimal("self_reserve", "Self reserve", "Health reserve after estimated self damage.", 6.0, 0.0, 20.0, 0.5),
                    decimal("friend_reserve", "Friend reserve", "Minimum friend health reserve.", 6.0, 0.0, 20.0, 0.5),
                    decimal("minimum_health", "Minimum health", "Do not act below this health plus absorption.", 12.0, 1.0, 40.0, 0.5),
                    integer("action_delay", "Action delay", "Ticks between confirmed setup actions.", 6, 0, 40),
                    integer("cleanup_minimum_durability", "Cleanup durability", "Minimum remaining tool durability for owned-block cleanup.", 5, 0, 1000),
                    integer("confirmation_ticks", "Confirmation ticks", "Maximum server-reflection confirmation time.", 8, 2, 40),
                    integer("maximum_retries", "Maximum retries", "Maximum bounded stage retries.", 1, 0, 3),
                    integer("failure_delay", "Failure delay", "Cooldown after an unconfirmed setup.", 60, 1, 200),
                    integer("cleanup_timeout", "Cleanup timeout", "Maximum ticks spent removing owned setup blocks.", 80, 10, 200)
            );
            case "safe_walk" -> List.of(decimal(
                    "look_ahead",
                    "Look ahead",
                    "Distance ahead checked by vanilla edge trimming.",
                    0.45,
                    0.20,
                    0.80,
                    0.05
            ));
            case "auto_center" -> List.of(
                    decimal("speed", "Speed", "Maximum centering speed per tick.", 0.12, 0.03, 0.25, 0.01),
                    decimal("tolerance", "Tolerance", "Distance from the block center accepted as centered.", 0.04, 0.01, 0.15, 0.01)
            );
            case "hole_snap" -> List.of(
                    integer("radius", "Radius", "Maximum bounded horizontal hole-search radius.", 3, 1, 5),
                    decimal("speed", "Speed", "Maximum safe snapping speed per tick.", 0.20, 0.05, 0.35, 0.01)
            );
            case "step" -> List.of(decimal(
                    "height",
                    "Height",
                    "Maximum client step height using an owned transient attribute.",
                    1.0,
                    0.6,
                    1.5,
                    0.1
            ));
            case "no_fall" -> List.of(decimal(
                    "trigger_distance",
                    "Trigger distance",
                    "Fall distance before one bounded vanilla glide attempt.",
                    3.2,
                    2.5,
                    10.0,
                    0.1
            ));
            case "fast_swim" -> List.of(decimal(
                    "speed",
                    "Speed",
                    "Maximum horizontal swimming speed.",
                    0.22,
                    0.12,
                    0.36,
                    0.01
            ));
            case "jesus" -> List.of(decimal(
                    "buoyancy",
                    "Buoyancy",
                    "Bounded upward assistance at a stable water surface.",
                    0.08,
                    0.02,
                    0.12,
                    0.01
            ));
            case "elytra_swap" -> List.of(
                    decimal("fall_distance", "Fall distance", "Fall distance before equipping a usable elytra.", 1.5, 0.5, 8.0, 0.1),
                    integer("minimum_durability", "Minimum durability", "Required remaining elytra durability.", 8, 2, 100),
                    bool("restore_armor", "Restore armor", "Restore the displaced chest item after landing.", true)
            );
            case "elytra_control" -> List.of(
                    decimal("cruise_speed", "Cruise speed", "Maximum horizontal elytra cruise speed.", 1.25, 0.4, 2.0, 0.05),
                    decimal("acceleration", "Acceleration", "Maximum horizontal velocity change per tick.", 0.04, 0.01, 0.12, 0.01),
                    decimal("vertical_speed", "Vertical speed", "Maximum assisted climb or descent speed.", 0.25, 0.05, 0.5, 0.01)
            );
            case "ground_speed" -> List.of(
                    decimal("speed", "Speed", "Maximum grounded horizontal speed.", 0.31, 0.20, 0.45, 0.01),
                    decimal("acceleration", "Acceleration", "Maximum grounded velocity change per tick.", 0.06, 0.01, 0.12, 0.01)
            );
            case "no_rotate" -> List.of(
                    bool("preserve_yaw", "Preserve yaw", "Keep the local camera yaw while accepting server position corrections.", true),
                    bool("preserve_pitch", "Preserve pitch", "Keep the local camera pitch while accepting server position corrections.", true)
            );
            case "player_esp" -> List.of(
                    decimal("range", "Range", "Maximum player highlight distance.", 128.0, 16.0, 512.0, 8.0),
                    color("player_color", "Player color", "ARGB color for non-friend players.", "CC55AAFF"),
                    color("friend_color", "Friend color", "ARGB color for friends.", "CC55FF88"),
                    bool("show_friends", "Show friends", "Highlight players on the friend list.", true),
                    bool("show_self", "Show self", "Highlight the local player in third person.", false),
                    bool("fill", "Fill", "Draw translucent player boxes.", true),
                    bool("outline", "Outline", "Draw player box outlines.", true)
            );
            case "tracers" -> List.of(
                    decimal("range", "Range", "Maximum tracer distance.", 192.0, 16.0, 512.0, 8.0),
                    color("player_color", "Player color", "ARGB tracer color for non-friends.", "DDFF6666"),
                    color("friend_color", "Friend color", "ARGB tracer color for friends.", "DD55FF88"),
                    bool("show_friends", "Show friends", "Draw tracers to friends.", true),
                    bool("show_self", "Show self", "Draw a tracer to the local player in third person.", false),
                    decimal("line_width", "Line width", "Preferred tracer width in pixels.", 1.5, 0.5, 4.0, 0.5)
            );
            case "nametags" -> List.of(
                    decimal("range", "Range", "Maximum enhanced nametag distance.", 128.0, 16.0, 512.0, 8.0),
                    color("player_color", "Player color", "ARGB nametag color for non-friends.", "FFFFFFFF"),
                    color("friend_color", "Friend color", "ARGB nametag color for friends.", "FF55FF88"),
                    color("background_color", "Background", "ARGB nametag background color.", "99000000"),
                    bool("show_friends", "Show friends", "Show enhanced nametags for friends.", true),
                    bool("show_self", "Show self", "Show the local nametag in third person.", false),
                    bool("show_health", "Show health", "Include health and absorption.", true),
                    bool("show_distance", "Show distance", "Include distance from the camera.", true),
                    bool("show_equipment", "Show equipment", "Include visible equipment.", true),
                    decimal("scale", "Scale", "Base enhanced nametag scale.", 1.0, 0.5, 2.5, 0.1)
            );
            case "storage_esp" -> List.of(
                    integer("range", "Range", "Maximum storage highlight distance.", 96, 16, 256),
                    color("color", "Color", "ARGB storage highlight color.", "CCFFB52E"),
                    bool("include_shulkers", "Include shulkers", "Highlight placed shulker boxes.", true)
            );
            case "hole_esp" -> List.of(
                    integer("range", "Range", "Horizontal combat-hole scan distance.", 24, 4, 64),
                    color("safe_color", "Safe color", "ARGB color for bedrock holes.", "CC32D26E"),
                    color("mixed_color", "Mixed color", "ARGB color for bedrock and obsidian holes.", "CCE4B640"),
                    color("unsafe_color", "Unsafe color", "ARGB color for breakable-wall holes.", "CCD94A4A"),
                    bool("show_unsafe", "Show unsafe", "Also display otherwise valid breakable holes.", false)
            );
            case "block_esp" -> List.of(
                    text(
                            "targets",
                            "Target blocks",
                            "Comma-separated namespaced block identifiers.",
                            "minecraft:ancient_debris,minecraft:nether_portal,minecraft:end_portal_frame",
                            2_048
                    ),
                    integer("range", "Range", "Maximum block-search distance.", 64, 8, 192),
                    integer("scan_budget", "Scan budget", "Maximum block positions inspected per tick.", 2_048, 128, 16_384),
                    color("color", "Color", "ARGB matching-block highlight color.", "CC9B59FF")
            );
            case "trajectories" -> List.of(
                    decimal("range", "Range", "Maximum simulated projectile distance.", 96.0, 16.0, 256.0, 4.0),
                    integer("steps", "Simulation steps", "Maximum projectile simulation steps per frame.", 120, 20, 320),
                    color("color", "Line color", "ARGB projected-path color.", "E65AE6FF")
            );
            case "freecam" -> List.of(
                    decimal("speed", "Speed", "Free-camera blocks moved per tick.", 0.50, 0.05, 5.0, 0.05),
                    decimal("sprint_multiplier", "Sprint multiplier", "Free-camera sprint speed multiplier.", 2.0, 1.0, 5.0, 0.25)
            );
            case "xray" -> List.of(
                    text(
                            "visible_blocks",
                            "Visible blocks",
                            "Comma-separated blocks that remain fully visible.",
                            "minecraft:ancient_debris,minecraft:coal_ore,minecraft:copper_ore,"
                                    + "minecraft:deepslate_coal_ore,minecraft:deepslate_copper_ore,"
                                    + "minecraft:deepslate_diamond_ore,minecraft:deepslate_emerald_ore,"
                                    + "minecraft:deepslate_gold_ore,minecraft:deepslate_iron_ore,"
                                    + "minecraft:deepslate_lapis_ore,minecraft:deepslate_redstone_ore,"
                                    + "minecraft:diamond_ore,minecraft:emerald_ore,minecraft:gold_ore,"
                                    + "minecraft:iron_ore,minecraft:lapis_ore,minecraft:nether_gold_ore,"
                                    + "minecraft:nether_quartz_ore,minecraft:redstone_ore",
                            4_096
                    ),
                    integer(
                            "hidden_opacity",
                            "Hidden opacity",
                            "Opacity percentage for non-target blocks.",
                            0,
                            0,
                            100
                    ),
                    bool("auto_refresh", "Auto refresh", "Rebuild visible chunks after XRay settings change.", true),
                    integer("refresh_delay", "Refresh delay", "Debounce ticks before a chunk rebuild.", 4, 1, 40)
            );
            case "chams" -> List.of(
                    visibleColor("color", "Color", "ARGB tint and non-zero opacity for player models.", "A0FF5555"),
                    bool("show_self", "Show self", "Apply chams to the local player in third person.", false)
            );
            case "new_chunks" -> List.of(
                    integer("scan_radius", "Scan radius", "Maximum loaded-chunk radius around the player.", 12, 2, 32),
                    integer("scan_interval", "Scan interval", "Ticks between loaded-chunk scans.", 5, 1, 40),
                    integer("lifetime", "Lifetime", "Seconds that observations remain visible.", 300, 10, 3_600),
                    integer("maximum_entries", "Maximum entries", "Maximum recent chunk observations retained.", 1_024, 64, 8_192),
                    color("color", "Color", "ARGB new-chunk overlay color.", "9900D7FF"),
                    bool("filled", "Filled", "Draw a translucent chunk fill.", true),
                    decimal("line_width", "Line width", "Preferred chunk outline width.", 1.5, 0.5, 5.0, 0.5)
            );
            case "logout_spots" -> List.of(
                    integer("lifetime", "Lifetime", "Seconds that logout spots remain visible.", 900, 30, 3_600),
                    integer("maximum_entries", "Maximum entries", "Maximum logout spots retained.", 128, 8, 1_024),
                    color("color", "Color", "ARGB logout-spot overlay color.", "CCFF6B6B"),
                    bool("show_name", "Show name", "Display the vanished player's name.", true),
                    bool("tracer", "Tracer", "Draw a tracer to each logout spot.", false),
                    decimal("line_width", "Line width", "Preferred logout-spot outline width.", 1.5, 0.5, 5.0, 0.5)
            );
            case "stash_finder" -> List.of(
                    integer("range", "Range", "Horizontal block radius covered by incremental scans.", 256, 32, 512),
                    integer("scan_budget", "Scan budget", "Maximum chunks and block entities inspected per tick.", 128, 16, 2_048),
                    integer("minimum_containers", "Minimum containers", "Nearby storage count required to report a stash.", 6, 2, 128),
                    integer("maximum_entries", "Maximum entries", "Maximum stash clusters retained for this session.", 128, 8, 1_024)
            );
            case "auto_armor" -> List.of(
                    bool("preserve_elytra", "Preserve elytra", "Do not replace an equipped elytra.", true),
                    integer("delay", "Delay", "Ticks between armor inventory actions.", 4, 1, 20)
            );
            case "replenish" -> List.of(
                    integer("threshold", "Threshold", "Top up a hotbar stack at or below this count.", 16, 1, 63),
                    integer("delay", "Delay", "Ticks between replenishment actions.", 4, 1, 20)
            );
            case "chest_swap" -> List.of(integer(
                    "minimum_durability",
                    "Minimum durability",
                    "Ignore chest items at or below this remaining durability.",
                    10,
                    0,
                    100
            ));
            case "auto_mend" -> List.of(
                    integer("start_at", "Start at", "Begin mending at this armor durability percentage.", 65, 5, 95),
                    integer("stop_at", "Stop at", "Stop when every armor piece reaches this percentage.", 90, 10, 100),
                    integer("delay", "Delay", "Ticks between experience bottles.", 2, 1, 10),
                    bool("require_sneak", "Require sneak", "Only mend while the sneak key is held.", true)
            );
            case "fast_use" -> List.of(
                    integer("delay", "Delay", "Ticks between repeated immediate-item uses.", 2, 2, 10),
                    bool("experience_bottles", "XP bottles", "Repeat experience bottle use.", true),
                    bool("projectiles", "Eggs and snowballs", "Repeat egg and snowball use.", false),
                    bool("pearls", "Ender pearls", "Repeat ender pearl use while respecting cooldown.", false),
                    bool("fireworks", "Fireworks", "Repeat firework use while gliding.", false)
            );
            case "inventory_manager" -> List.of(integer(
                    "delay",
                    "Delay",
                    "Ticks between conservative stack-consolidation actions.",
                    8,
                    2,
                    40
            ));
            case "auto_craft" -> List.of(
                    text("recipes", "Recipe whitelist", "Comma-separated allowed recipe selectors.", "", 4_096),
                    text("outputs", "Output whitelist", "Comma-separated allowed output item identifiers.", "", 4_096),
                    integer("delay", "Action delay", "Ticks between recipe placement and result pickup.", 10, 2, 100),
                    integer("maximum_crafts", "Maximum crafts", "Maximum crafts per crafting-screen session.", 8, 1, 64),
                    integer("preferred_hotbar_slot", "Preferred hotbar slot", "First output hotbar slot, one through nine.", 9, 1, 9)
            );
            case "baritone_navigator" -> List.of(
                    integer("target_x", "Target X", "Destination X block coordinate.", 0, -30_000_000, 30_000_000),
                    integer("target_y", "Target Y", "Destination Y block coordinate.", 64, -64, 319),
                    integer("target_z", "Target Z", "Destination Z block coordinate.", 0, -30_000_000, 30_000_000),
                    bool("confirm_target", "Confirm target", "Arm exactly one navigation start.", false)
            );
            default -> List.of();
        };
    }

    static WalkMovementAutomation26.Configuration walkMovementConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = WalkMovementAutomation26.DEFAULT_CONFIGURATION;
        return new WalkMovementAutomation26.Configuration(
                decimalValue(modules, "safe_walk", "look_ahead", defaults.safeWalkLookAhead()),
                decimalValue(modules, "auto_center", "speed", defaults.autoCenterSpeed()),
                decimalValue(modules, "auto_center", "tolerance", defaults.autoCenterTolerance()),
                integerValue(modules, "hole_snap", "radius", defaults.holeRadius()),
                decimalValue(modules, "hole_snap", "speed", defaults.holeSnapSpeed()),
                defaults.holeSnapTolerance(),
                defaults.maximumHoleScans(),
                decimalValue(modules, "step", "height", defaults.stepHeight()),
                defaults.maximumStepIncreasePerTick()
        );
    }

    static FallWaterMovementAutomation26.Configuration
            fallWaterMovementConfiguration(ModuleRegistry modules) {
        var defaults = FallWaterMovementAutomation26.DEFAULT_CONFIGURATION;
        return new FallWaterMovementAutomation26.Configuration(
                decimalValue(
                        modules,
                        "no_fall",
                        "trigger_distance",
                        defaults.noFallTriggerDistance()
                ),
                decimalValue(
                        modules,
                        "fast_swim",
                        "speed",
                        defaults.fastSwimSpeed()
                ),
                decimalValue(
                        modules,
                        "jesus",
                        "buoyancy",
                        defaults.jesusBuoyancy()
                )
        );
    }

    static ElytraSwapAutomation26.Configuration elytraSwapConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = ElytraSwapAutomation26.DEFAULT_CONFIGURATION;
        return new ElytraSwapAutomation26.Configuration(
                decimalValue(
                        modules,
                        "elytra_swap",
                        "fall_distance",
                        defaults.fallDistance()
                ),
                integerValue(
                        modules,
                        "elytra_swap",
                        "minimum_durability",
                        defaults.minimumDurability()
                ),
                booleanValue(
                        modules,
                        "elytra_swap",
                        "restore_armor",
                        defaults.restoreArmor()
                ),
                defaults.confirmationTimeoutTicks(),
                defaults.stableConfirmationTicks(),
                defaults.actionCooldownTicks(),
                defaults.failureCooldownTicks()
        );
    }

    static ElytraControlAutomation26.Configuration elytraControlConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = ElytraControlAutomation26.DEFAULT_CONFIGURATION;
        return new ElytraControlAutomation26.Configuration(
                decimalValue(
                        modules,
                        "elytra_control",
                        "cruise_speed",
                        defaults.cruiseSpeed()
                ),
                decimalValue(
                        modules,
                        "elytra_control",
                        "acceleration",
                        defaults.acceleration()
                ),
                decimalValue(
                        modules,
                        "elytra_control",
                        "vertical_speed",
                        defaults.verticalSpeed()
                ),
                defaults.maximumPitchChangePerTick(),
                defaults.climbPitchDegrees(),
                defaults.descentPitchDegrees(),
                defaults.manualPitchOverrideDegrees(),
                defaults.manualPitchSuppressionTicks()
        );
    }

    static MovementInputAutomation26.Configuration movementInputConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = MovementInputAutomation26.Configuration.DEFAULT;
        boolean preserveYaw = booleanValue(
                modules,
                "no_rotate",
                "preserve_yaw",
                defaults.noRotate().preserveYaw()
        );
        boolean preservePitch = booleanValue(
                modules,
                "no_rotate",
                "preserve_pitch",
                defaults.noRotate().preservePitch()
        );
        return new MovementInputAutomation26.Configuration(
                decimalValue(
                        modules,
                        "ground_speed",
                        "speed",
                        defaults.groundSpeed().targetSpeed()
                ),
                decimalValue(
                        modules,
                        "ground_speed",
                        "acceleration",
                        defaults.groundSpeed().accelerationPerTick()
                ),
                preserveYaw,
                preservePitch
        );
    }

    static CombatInventoryAutomation26.Configuration inventoryConfiguration(
            ModuleRegistry modules,
            boolean autoTotemEnabled
    ) {
        CombatInventoryAutomation26.Configuration defaults =
                CombatInventoryAutomation26.DEFAULT_CONFIGURATION;
        return new CombatInventoryAutomation26.Configuration(
                (float) decimalValue(
                        modules,
                        "auto_totem",
                        "health",
                        defaults.autoTotemHealth()
                ),
                (float) decimalValue(
                        modules,
                        "offhand",
                        "emergency_health",
                        defaults.emergencyTotemHealth()
                ),
                offhandValue(
                        stringValue(
                                modules,
                                "offhand",
                                "item",
                                defaults.preferredOffhand().name()
                        ),
                        defaults.preferredOffhand()
                ),
                booleanValue(
                        modules,
                        "offhand",
                        "emergency_totem",
                        defaults.emergencyTotem()
                ),
                booleanValue(
                        modules,
                        "auto_totem",
                        "replace_offhand",
                        defaults.autoTotemReplaceOffhand()
                ),
                integerValue(
                        modules,
                        "auto_totem",
                        "delay",
                        defaults.autoTotemCooldownTicks()
                ),
                booleanValue(
                        modules,
                        "offhand",
                        "replace",
                        defaults.offhandReplaceOffhand()
                ),
                integerValue(
                        modules,
                        "offhand",
                        "delay",
                        defaults.offhandCooldownTicks()
                ),
                integerValue(
                        modules,
                        "auto_weapon",
                        "minimum_durability",
                        defaults.minimumWeaponDurability()
                )
        );
    }

    static AttackConfiguration attackConfiguration(
            ModuleRegistry modules,
            boolean triggerBotEnabled
    ) {
        String module = triggerBotEnabled ? "trigger_bot" : "kill_aura";
        return new AttackConfiguration(
                decimalValue(
                        modules,
                        module,
                        "range",
                        CombatAttackAutomation26.DEFAULT_ATTACK_RANGE
                ),
                (float) decimalValue(
                        modules,
                        module,
                        "cooldown",
                        CombatAttackAutomation26.DEFAULT_COOLDOWN_THRESHOLD
                ),
                integerValue(
                        modules,
                        module,
                        "minimum_ticks",
                        CombatAttackAutomation26.DEFAULT_MINIMUM_ATTACK_TICKS
                )
        );
    }

    static CombatCrystalMineAutomation26.Configuration crystalMineConfiguration(
            ModuleRegistry modules
    ) {
        CombatCrystalMineAutomation26.Configuration defaults =
                CombatCrystalMineAutomation26.Configuration.defaults();
        return new CombatCrystalMineAutomation26.Configuration(
                decimalValue(modules, "auto_crystal", "target_range", defaults.targetRange()),
                decimalValue(modules, "auto_crystal", "break_range", defaults.breakRange()),
                decimalValue(modules, "auto_crystal", "place_range", defaults.placeRange()),
                decimalValue(modules, "auto_mine", "range", defaults.mineRange()),
                decimalValue(modules, "auto_crystal", "minimum_damage", defaults.minimumTargetDamage()),
                decimalValue(modules, "auto_crystal", "maximum_self_damage", defaults.maximumSelfDamage()),
                decimalValue(modules, "auto_crystal", "maximum_friend_damage", defaults.maximumFriendDamage()),
                decimalValue(modules, "auto_crystal", "self_reserve", defaults.selfSafetyReserve()),
                decimalValue(modules, "auto_crystal", "friend_reserve", defaults.friendSafetyReserve()),
                decimalValue(modules, "auto_crystal", "minimum_health", defaults.minimumCrystalHealth()),
                decimalValue(modules, "auto_mine", "minimum_health", defaults.minimumMineHealth()),
                integerValue(modules, "auto_crystal", "action_delay", defaults.crystalActionCooldownTicks()),
                integerValue(modules, "auto_crystal", "failure_delay", defaults.crystalFailureCooldownTicks()),
                integerValue(modules, "auto_mine", "action_delay", defaults.mineActionCooldownTicks())
        );
    }

    static CombatDefensiveConstructionAutomation26.Configuration
    defensiveConstructionConfiguration(
            ModuleRegistry modules,
            boolean surroundEnabled,
            boolean holeFillEnabled,
            boolean selfTrapEnabled,
            boolean autoTrapEnabled,
            boolean burrowEnabled
    ) {
        var surround =
                CombatDefensiveConstructionAutomation26
                        .SurroundConfiguration.defaults();
        var holeFill =
                CombatDefensiveConstructionAutomation26
                        .HoleFillConfiguration.defaults();
        var selfTrap =
                CombatDefensiveConstructionAutomation26
                        .SelfTrapConfiguration.defaults();
        var autoTrap =
                CombatDefensiveConstructionAutomation26
                        .AutoTrapConfiguration.defaults();
        var burrow =
                CombatDefensiveConstructionAutomation26
                        .BurrowConfiguration.defaults();
        return new CombatDefensiveConstructionAutomation26.Configuration(
                new CombatDefensiveConstructionAutomation26
                        .SurroundConfiguration(
                        decimalValue(
                                modules,
                                "surround",
                                "placement_range",
                                surround.placementRange()
                        ),
                        booleanValue(modules, "surround", "floor", surround.floor()),
                        decimalValue(modules, "surround", "minimum_health", surround.minimumHealth()),
                        integerValue(modules, "surround", "action_delay", surround.actionCooldownTicks()),
                        integerValue(modules, "surround", "failure_delay", surround.failureCooldownTicks()),
                        surround.confirmationTimeoutTicks(),
                        surround.maximumRetries()
                ),
                new CombatDefensiveConstructionAutomation26
                        .HoleFillConfiguration(
                        decimalValue(modules, "hole_fill", "target_range", holeFill.targetRange()),
                        decimalValue(modules, "hole_fill", "placement_range", holeFill.placementRange()),
                        integerValue(modules, "hole_fill", "scan_radius", holeFill.scanRadius()),
                        decimalValue(modules, "hole_fill", "enemy_radius", holeFill.enemyRadius()),
                        decimalValue(modules, "hole_fill", "minimum_health", holeFill.minimumHealth()),
                        integerValue(modules, "hole_fill", "action_delay", holeFill.actionCooldownTicks()),
                        integerValue(modules, "hole_fill", "failure_delay", holeFill.failureCooldownTicks()),
                        holeFill.confirmationTimeoutTicks(),
                        holeFill.maximumRetries(),
                        holeFill.maximumPlayerScans(),
                        holeFill.maximumHoleScans(),
                        holeFill.maximumFriendEntries()
                ),
                new CombatDefensiveConstructionAutomation26
                        .SelfTrapConfiguration(
                        decimalValue(modules, "self_trap", "placement_range", selfTrap.placementRange()),
                        booleanValue(modules, "self_trap", "head_sides", selfTrap.headSides()),
                        decimalValue(modules, "self_trap", "minimum_health", selfTrap.minimumHealth()),
                        integerValue(modules, "self_trap", "action_delay", selfTrap.actionCooldownTicks()),
                        integerValue(modules, "self_trap", "failure_delay", selfTrap.failureCooldownTicks()),
                        selfTrap.confirmationTimeoutTicks(),
                        selfTrap.maximumRetries()
                ),
                new CombatDefensiveConstructionAutomation26
                        .AutoTrapConfiguration(
                        decimalValue(modules, "auto_trap", "target_range", autoTrap.targetRange()),
                        decimalValue(modules, "auto_trap", "placement_range", autoTrap.placementRange()),
                        booleanValue(modules, "auto_trap", "head_sides", autoTrap.headSides()),
                        decimalValue(modules, "auto_trap", "minimum_health", autoTrap.minimumHealth()),
                        integerValue(modules, "auto_trap", "action_delay", autoTrap.actionCooldownTicks()),
                        integerValue(modules, "auto_trap", "failure_delay", autoTrap.failureCooldownTicks()),
                        autoTrap.confirmationTimeoutTicks(),
                        autoTrap.maximumRetries(),
                        autoTrap.maximumPlayerScans(),
                        autoTrap.maximumFriendEntries()
                ),
                new CombatDefensiveConstructionAutomation26
                        .BurrowConfiguration(
                        decimalValue(modules, "burrow", "placement_range", burrow.placementRange()),
                        decimalValue(modules, "burrow", "minimum_health", burrow.minimumHealth()),
                        burrow.actionCooldownTicks(),
                        integerValue(modules, "burrow", "failure_delay", burrow.failureCooldownTicks()),
                        burrow.confirmationTimeoutTicks(),
                        burrow.maximumRetries(),
                        booleanValue(modules, "burrow", "auto_jump", burrow.autoJump()),
                        integerValue(modules, "burrow", "timeout", burrow.timeoutTicks()),
                        decimalValue(modules, "burrow", "minimum_rise", burrow.minimumRise())
                )
        );
    }

    static CombatBedAnchorAutomation26.Configuration anchorConfiguration(
            ModuleRegistry modules
    ) {
        return explosiveConfiguration(modules, "anchor_aura");
    }

    static CombatBedAnchorAutomation26.Configuration bedConfiguration(
            ModuleRegistry modules
    ) {
        return explosiveConfiguration(modules, "bed_aura");
    }

    private static CombatBedAnchorAutomation26.Configuration
    explosiveConfiguration(ModuleRegistry modules, String moduleId) {
        var defaults = CombatBedAnchorAutomation26.Configuration.defaults();
        return new CombatBedAnchorAutomation26.Configuration(
                decimalValue(
                        modules,
                        moduleId,
                        "target_range",
                        defaults.targetRange()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "use_range",
                        defaults.useRange()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "place_range",
                        defaults.placeRange()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "minimum_damage",
                        defaults.minimumTargetDamage()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "maximum_self_damage",
                        defaults.maximumSelfDamage()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "maximum_friend_damage",
                        defaults.maximumFriendDamage()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "self_reserve",
                        defaults.selfSafetyReserve()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "friend_reserve",
                        defaults.friendSafetyReserve()
                ),
                decimalValue(
                        modules,
                        moduleId,
                        "minimum_health",
                        defaults.minimumHealth()
                ),
                integerValue(
                        modules,
                        moduleId,
                        "action_delay",
                        defaults.actionCooldownTicks()
                ),
                integerValue(
                        modules,
                        moduleId,
                        "failure_delay",
                        defaults.failureCooldownTicks()
                )
        );
    }

    static CombatBowAimAutomation26.Configuration bowAimConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = CombatBowAimAutomation26.DEFAULT_CONFIGURATION;
        return new CombatBowAimAutomation26.Configuration(
                decimalValue(modules, "bow_aim", "range", defaults.range()),
                decimalValue(
                        modules,
                        "bow_aim",
                        "bow_speed",
                        defaults.fullBowSpeed()
                ),
                decimalValue(
                        modules,
                        "bow_aim",
                        "crossbow_speed",
                        defaults.crossbowSpeed()
                ),
                decimalValue(
                        modules,
                        "bow_aim",
                        "gravity",
                        defaults.gravity()
                ),
                integerValue(
                        modules,
                        "bow_aim",
                        "lead_ticks",
                        (int) defaults.maximumLeadTicks()
                ),
                decimalValue(
                        modules,
                        "bow_aim",
                        "fov",
                        defaults.fovDegrees()
                ),
                decimalValue(
                        modules,
                        "bow_aim",
                        "rotation_speed",
                        defaults.maximumRotationDegreesPerTick()
                ),
                integerValue(
                        modules,
                        "bow_aim",
                        "minimum_draw",
                        defaults.minimumBowDrawTicks()
                ),
                decimalValue(
                        modules,
                        "bow_aim",
                        "manual_override",
                        defaults.manualOverrideThresholdDegrees()
                ),
                integerValue(
                        modules,
                        "bow_aim",
                        "override_ticks",
                        defaults.manualOverrideSuppressionTicks()
                )
        );
    }

    static CombatQuiverAutomation26.Configuration quiverConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = CombatQuiverAutomation26.DEFAULT_CONFIGURATION;
        return new CombatQuiverAutomation26.Configuration(
                integerValue(
                        modules,
                        "quiver",
                        "draw_ticks",
                        defaults.drawTicks()
                ),
                (float) decimalValue(
                        modules,
                        "quiver",
                        "minimum_health",
                        defaults.minimumHealth()
                ),
                integerValue(
                        modules,
                        "quiver",
                        "minimum_durability",
                        defaults.minimumBowDurability()
                ),
                integerValue(
                        modules,
                        "quiver",
                        "minimum_effect_ticks",
                        defaults.minimumEffectRemainingTicks()
                ),
                integerValue(
                        modules,
                        "quiver",
                        "confirmation_ticks",
                        defaults.confirmationTicks()
                ),
                integerValue(
                        modules,
                        "quiver",
                        "shot_acceptance_ticks",
                        defaults.shotAcceptanceTicks()
                ),
                integerValue(
                        modules,
                        "quiver",
                        "success_delay",
                        defaults.successCooldownTicks()
                ),
                integerValue(
                        modules,
                        "quiver",
                        "failure_delay",
                        defaults.failureCooldownTicks()
                )
        );
    }

    static CombatSiegeAutomation26.CityConfiguration
    cityBreakerConfiguration(ModuleRegistry modules) {
        var defaults = CombatSiegeAutomation26.CityConfiguration.defaults();
        return new CombatSiegeAutomation26.CityConfiguration(
                decimalValue(modules, "city_breaker", "target_range", defaults.targetRange()),
                decimalValue(modules, "city_breaker", "mine_range", defaults.mineRange()),
                decimalValue(modules, "city_breaker", "minimum_health", defaults.minimumHealth()),
                integerValue(modules, "city_breaker", "minimum_durability", defaults.minimumToolDurability()),
                integerValue(modules, "city_breaker", "confirmation_ticks", defaults.confirmationTicks()),
                integerValue(modules, "city_breaker", "maximum_retries", defaults.maximumRetries()),
                integerValue(modules, "city_breaker", "action_delay", defaults.actionCooldownTicks()),
                integerValue(modules, "city_breaker", "failure_delay", defaults.failureCooldownTicks())
        );
    }

    static CombatSiegeAutomation26.PistonConfiguration
    pistonCrystalConfiguration(ModuleRegistry modules) {
        var defaults = CombatSiegeAutomation26.PistonConfiguration.defaults();
        return new CombatSiegeAutomation26.PistonConfiguration(
                decimalValue(modules, "piston_crystal", "target_range", defaults.targetRange()),
                decimalValue(modules, "piston_crystal", "place_range", defaults.placeRange()),
                decimalValue(modules, "piston_crystal", "break_range", defaults.breakRange()),
                decimalValue(modules, "piston_crystal", "minimum_health", defaults.minimumHealth()),
                decimalValue(modules, "piston_crystal", "minimum_damage", defaults.minimumTargetDamage()),
                decimalValue(modules, "piston_crystal", "maximum_self_damage", defaults.maximumSelfDamage()),
                decimalValue(modules, "piston_crystal", "maximum_friend_damage", defaults.maximumFriendDamage()),
                decimalValue(modules, "piston_crystal", "self_reserve", defaults.selfSafetyReserve()),
                decimalValue(modules, "piston_crystal", "friend_reserve", defaults.friendSafetyReserve()),
                integerValue(
                        modules,
                        "piston_crystal",
                        "cleanup_minimum_durability",
                        defaults.cleanupMinimumToolDurability()
                ),
                integerValue(modules, "piston_crystal", "confirmation_ticks", defaults.confirmationTicks()),
                integerValue(modules, "piston_crystal", "maximum_retries", defaults.maximumRetries()),
                integerValue(modules, "piston_crystal", "action_delay", defaults.actionCooldownTicks()),
                integerValue(modules, "piston_crystal", "failure_delay", defaults.failureCooldownTicks()),
                integerValue(modules, "piston_crystal", "cleanup_timeout", defaults.cleanupTimeoutTicks())
        );
    }

    static VisualOverlayConfiguration26 visualOverlayConfiguration(
            ModuleRegistry modules
    ) {
        VisualOverlayConfiguration26 defaults =
                VisualOverlayConfiguration26.DISABLED;
        var player = defaults.playerEsp();
        var tracers = defaults.tracers();
        var nametags = defaults.nametags();
        var storage = defaults.storageEsp();
        var holes = defaults.holeEsp();
        var blocks = defaults.blockEsp();
        var trajectories = defaults.trajectories();
        return new VisualOverlayConfiguration26(
                new VisualOverlayConfiguration26.PlayerEsp(
                        enabledValue(modules, "player_esp"),
                        decimalValue(
                                modules,
                                "player_esp",
                                "range",
                                player.range()
                        ),
                        colorValue(
                                modules,
                                "player_esp",
                                "player_color",
                                player.playerColor()
                        ),
                        colorValue(
                                modules,
                                "player_esp",
                                "friend_color",
                                player.friendColor()
                        ),
                        booleanValue(
                                modules,
                                "player_esp",
                                "show_friends",
                                player.showFriends()
                        ),
                        booleanValue(
                                modules,
                                "player_esp",
                                "show_self",
                                player.showSelf()
                        ),
                        booleanValue(
                                modules,
                                "player_esp",
                                "fill",
                                player.fill()
                        ),
                        booleanValue(
                                modules,
                                "player_esp",
                                "outline",
                                player.outline()
                        ),
                        player.renderCap()
                ),
                new VisualOverlayConfiguration26.Tracers(
                        enabledValue(modules, "tracers"),
                        decimalValue(
                                modules,
                                "tracers",
                                "range",
                                tracers.range()
                        ),
                        colorValue(
                                modules,
                                "tracers",
                                "player_color",
                                tracers.playerColor()
                        ),
                        colorValue(
                                modules,
                                "tracers",
                                "friend_color",
                                tracers.friendColor()
                        ),
                        booleanValue(
                                modules,
                                "tracers",
                                "show_friends",
                                tracers.showFriends()
                        ),
                        booleanValue(
                                modules,
                                "tracers",
                                "show_self",
                                tracers.showSelf()
                        ),
                        (float) decimalValue(
                                modules,
                                "tracers",
                                "line_width",
                                tracers.lineWidth()
                        ),
                        tracers.renderCap()
                ),
                new VisualOverlayConfiguration26.Nametags(
                        enabledValue(modules, "nametags"),
                        decimalValue(
                                modules,
                                "nametags",
                                "range",
                                nametags.range()
                        ),
                        colorValue(
                                modules,
                                "nametags",
                                "player_color",
                                nametags.playerColor()
                        ),
                        colorValue(
                                modules,
                                "nametags",
                                "friend_color",
                                nametags.friendColor()
                        ),
                        colorValue(
                                modules,
                                "nametags",
                                "background_color",
                                nametags.backgroundColor()
                        ),
                        booleanValue(
                                modules,
                                "nametags",
                                "show_friends",
                                nametags.showFriends()
                        ),
                        booleanValue(
                                modules,
                                "nametags",
                                "show_self",
                                nametags.showSelf()
                        ),
                        booleanValue(
                                modules,
                                "nametags",
                                "show_health",
                                nametags.showHealth()
                        ),
                        booleanValue(
                                modules,
                                "nametags",
                                "show_distance",
                                nametags.showDistance()
                        ),
                        booleanValue(
                                modules,
                                "nametags",
                                "show_equipment",
                                nametags.showEquipment()
                        ),
                        (float) decimalValue(
                                modules,
                                "nametags",
                                "scale",
                                nametags.scale()
                        ),
                        nametags.renderCap()
                ),
                new VisualOverlayConfiguration26.StorageEsp(
                        enabledValue(modules, "storage_esp"),
                        integerValue(
                                modules,
                                "storage_esp",
                                "range",
                                storage.range()
                        ),
                        colorValue(
                                modules,
                                "storage_esp",
                                "color",
                                storage.color()
                        ),
                        booleanValue(
                                modules,
                                "storage_esp",
                                "include_shulkers",
                                storage.includeShulkers()
                        ),
                        storage.renderCap()
                ),
                new VisualOverlayConfiguration26.HoleEsp(
                        enabledValue(modules, "hole_esp"),
                        integerValue(
                                modules,
                                "hole_esp",
                                "range",
                                holes.range()
                        ),
                        colorValue(
                                modules,
                                "hole_esp",
                                "safe_color",
                                holes.safeColor()
                        ),
                        colorValue(
                                modules,
                                "hole_esp",
                                "mixed_color",
                                holes.mixedColor()
                        ),
                        colorValue(
                                modules,
                                "hole_esp",
                                "unsafe_color",
                                holes.unsafeColor()
                        ),
                        booleanValue(
                                modules,
                                "hole_esp",
                                "show_unsafe",
                                holes.showUnsafe()
                        ),
                        holes.scanBudget(),
                        holes.renderCap()
                ),
                new VisualOverlayConfiguration26.BlockEsp(
                        enabledValue(modules, "block_esp"),
                        commaSeparatedValue(
                                modules,
                                "block_esp",
                                "targets",
                                blocks.targets(),
                                128
                        ),
                        integerValue(
                                modules,
                                "block_esp",
                                "range",
                                blocks.range()
                        ),
                        integerValue(
                                modules,
                                "block_esp",
                                "scan_budget",
                                blocks.scanBudget()
                        ),
                        colorValue(
                                modules,
                                "block_esp",
                                "color",
                                blocks.color()
                        ),
                        blocks.renderCap()
                ),
                new VisualOverlayConfiguration26.Trajectories(
                        enabledValue(modules, "trajectories"),
                        decimalValue(
                                modules,
                                "trajectories",
                                "range",
                                trajectories.range()
                        ),
                        integerValue(
                                modules,
                                "trajectories",
                                "steps",
                                trajectories.steps()
                        ),
                        colorValue(
                                modules,
                                "trajectories",
                                "color",
                                trajectories.color()
                        )
                )
        );
    }

    static FreecamController26.Configuration freecamConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = FreecamController26.Configuration.DEFAULT;
        return new FreecamController26.Configuration(
                decimalValue(
                        modules,
                        "freecam",
                        "speed",
                        defaults.speed()
                ),
                decimalValue(
                        modules,
                        "freecam",
                        "sprint_multiplier",
                        defaults.sprintMultiplier()
                )
        );
    }

    static XRayController26.Configuration xrayConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = XRayController26.Configuration.DEFAULT;
        return XRayController26.Configuration.of(
                commaSeparatedValue(
                        modules,
                        "xray",
                        "visible_blocks",
                        defaults.visibleBlocks(),
                        XRayController26.MAX_VISIBLE_BLOCKS
                ),
                integerValue(
                        modules,
                        "xray",
                        "hidden_opacity",
                        defaults.hiddenOpacity()
                ),
                booleanValue(
                        modules,
                        "xray",
                        "auto_refresh",
                        defaults.autoRefresh()
                ),
                integerValue(
                        modules,
                        "xray",
                        "refresh_delay",
                        defaults.refreshDelayTicks()
                )
        );
    }

    static ChamsController26.Configuration chamsConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = ChamsController26.Configuration.DEFAULT;
        int color = colorValue(
                modules,
                "chams",
                "color",
                defaults.color()
        );
        if ((color >>> 24) == 0) {
            color = defaults.color();
        }
        return new ChamsController26.Configuration(
                color,
                booleanValue(
                        modules,
                        "chams",
                        "show_self",
                        defaults.showSelf()
                )
        );
    }

    static WorldTrackerService26.Configuration worldTrackerConfiguration(
            ModuleRegistry modules
    ) {
        return new WorldTrackerService26.Configuration(
                new NewChunksDecisionEngine26.Config(
                        integerValue(
                                modules,
                                "new_chunks",
                                "scan_radius",
                                12
                        ),
                        128,
                        integerValue(
                                modules,
                                "new_chunks",
                                "lifetime",
                                300
                        ) * 20L,
                        integerValue(
                                modules,
                                "new_chunks",
                                "maximum_entries",
                                1_024
                        ),
                        2,
                        20L
                ),
                new LogoutSpotsDecisionEngine26.Config(
                        128,
                        512,
                        integerValue(
                                modules,
                                "logout_spots",
                                "maximum_entries",
                                128
                        ),
                        integerValue(
                                modules,
                                "logout_spots",
                                "lifetime",
                                900
                        ) * 20L,
                        2
                ),
                new StashFinderDecisionEngine26.Config(
                        integerValue(
                                modules,
                                "stash_finder",
                                "range",
                                256
                        ),
                        integerValue(
                                modules,
                                "stash_finder",
                                "scan_budget",
                                128
                        ),
                        integerValue(
                                modules,
                                "stash_finder",
                                "minimum_containers",
                                6
                        ),
                        integerValue(
                                modules,
                                "stash_finder",
                                "maximum_entries",
                                128
                        ),
                        16_384,
                        18_000L
                ),
                integerValue(
                        modules,
                        "new_chunks",
                        "scan_interval",
                        5
                )
        );
    }

    static WorldTrackerRenderService26.Configuration
            worldTrackerRenderConfiguration(ModuleRegistry modules) {
        WorldTrackerRenderService26.Configuration defaults =
                WorldTrackerRenderService26.Configuration.defaults();
        return new WorldTrackerRenderService26.Configuration(
                new WorldTrackerRenderService26.NewChunksRender(
                        colorValue(
                                modules,
                                "new_chunks",
                                "color",
                                defaults.newChunks().argb()
                        ),
                        booleanValue(
                                modules,
                                "new_chunks",
                                "filled",
                                defaults.newChunks().filled()
                        ),
                        (float) decimalValue(
                                modules,
                                "new_chunks",
                                "line_width",
                                defaults.newChunks().lineWidth()
                        ),
                        defaults.newChunks().maximumDistanceBlocks(),
                        defaults.newChunks().renderCap()
                ),
                new WorldTrackerRenderService26.LogoutSpotsRender(
                        colorValue(
                                modules,
                                "logout_spots",
                                "color",
                                defaults.logoutSpots().argb()
                        ),
                        booleanValue(
                                modules,
                                "logout_spots",
                                "show_name",
                                defaults.logoutSpots().showName()
                        ),
                        booleanValue(
                                modules,
                                "logout_spots",
                                "tracer",
                                defaults.logoutSpots().tracer()
                        ),
                        (float) decimalValue(
                                modules,
                                "logout_spots",
                                "line_width",
                                defaults.logoutSpots().lineWidth()
                        ),
                        defaults.logoutSpots().maximumDistanceBlocks(),
                        defaults.logoutSpots().renderCap(),
                        defaults.logoutSpots().labelScale()
                ),
                defaults.stashFinder()
        );
    }

    static AutoArmorAutomation26.Configuration autoArmorConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = AutoArmorAutomation26.DEFAULT_CONFIGURATION;
        return new AutoArmorAutomation26.Configuration(
                booleanValue(
                        modules,
                        "auto_armor",
                        "preserve_elytra",
                        defaults.preserveElytra()
                ),
                integerValue(
                        modules,
                        "auto_armor",
                        "delay",
                        defaults.actionCooldownTicks()
                ),
                defaults.minimumRemainingDurability(),
                defaults.minimumImprovement(),
                defaults.manualYieldTicks()
        );
    }

    static ReplenishAutomation26.Configuration replenishConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = ReplenishAutomation26.DEFAULT_CONFIGURATION;
        return new ReplenishAutomation26.Configuration(
                integerValue(
                        modules,
                        "replenish",
                        "threshold",
                        defaults.threshold()
                ),
                integerValue(
                        modules,
                        "replenish",
                        "delay",
                        defaults.delayTicks()
                ),
                defaults.failureCooldownTicks()
        );
    }

    static ChestSwapAutomation26.Configuration chestSwapConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = ChestSwapAutomation26.DEFAULT_CONFIGURATION;
        return new ChestSwapAutomation26.Configuration(
                integerValue(
                        modules,
                        "chest_swap",
                        "minimum_durability",
                        defaults.minimumDurability()
                ),
                defaults.actionCooldownTicks(),
                defaults.failureCooldownTicks(),
                defaults.maximumWaitTicks()
        );
    }

    static InventoryManagerAutomation26.Configuration
            inventoryManagerConfiguration(ModuleRegistry modules) {
        var defaults = InventoryManagerAutomation26.DEFAULT_CONFIGURATION;
        return new InventoryManagerAutomation26.Configuration(
                integerValue(
                        modules,
                        "inventory_manager",
                        "delay",
                        defaults.actionCooldownTicks()
                ),
                defaults.manualYieldTicks()
        );
    }

    static AutoMendDecisionEngine26.Configuration autoMendConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = AutoMendAutomation26.DEFAULT_CONFIGURATION;
        int start = integerValue(
                modules,
                "auto_mend",
                "start_at",
                defaults.startAtPercent()
        );
        int stop = Math.max(
                start,
                integerValue(
                        modules,
                        "auto_mend",
                        "stop_at",
                        defaults.stopAtPercent()
                )
        );
        return new AutoMendDecisionEngine26.Configuration(
                start,
                stop,
                integerValue(
                        modules,
                        "auto_mend",
                        "delay",
                        defaults.delayTicks()
                ),
                booleanValue(
                        modules,
                        "auto_mend",
                        "require_sneak",
                        defaults.requireSneak()
                ),
                defaults.confirmationTimeoutTicks()
        );
    }

    static FastUseAutomation26.Configuration fastUseConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = FastUseAutomation26.DEFAULT_CONFIGURATION;
        var policy = defaults.policy();
        return new FastUseAutomation26.Configuration(
                new FastUseDecisionEngine26.Configuration(
                        integerValue(
                                modules,
                                "fast_use",
                                "delay",
                                policy.delayTicks()
                        ),
                        booleanValue(
                                modules,
                                "fast_use",
                                "experience_bottles",
                                policy.experienceBottles()
                        ),
                        booleanValue(
                                modules,
                                "fast_use",
                                "projectiles",
                                policy.projectiles()
                        ),
                        booleanValue(
                                modules,
                                "fast_use",
                                "pearls",
                                policy.enderPearls()
                        ),
                        booleanValue(
                                modules,
                                "fast_use",
                                "fireworks",
                                policy.fireworks()
                        )
                ),
                defaults.maximumActionsPerWindow(),
                defaults.windowTicks(),
                defaults.minimumSpacingTicks()
        );
    }

    static AutoCraftAutomation26.Configuration autoCraftConfiguration(
            ModuleRegistry modules
    ) {
        var defaults = AutoCraftAutomation26.DEFAULT_CONFIGURATION;
        int delay = integerValue(
                modules,
                "auto_craft",
                "delay",
                defaults.actionDelayTicks()
        );
        return new AutoCraftAutomation26.Configuration(
                commaSeparatedValue(
                        modules,
                        "auto_craft",
                        "recipes",
                        defaults.recipeWhitelist(),
                        256
                ),
                commaSeparatedValue(
                        modules,
                        "auto_craft",
                        "outputs",
                        defaults.outputWhitelist(),
                        256
                ),
                delay,
                integerValue(
                        modules,
                        "auto_craft",
                        "maximum_crafts",
                        defaults.maximumCrafts()
                ),
                integerValue(
                        modules,
                        "auto_craft",
                        "preferred_hotbar_slot",
                        defaults.preferredHotbarSlot() + 1
                ) - 1,
                Math.max(defaults.confirmationTimeoutTicks(), delay),
                defaults.maximumActionsPerWindow(),
                defaults.actionWindowTicks(),
                defaults.minimumActionSpacingTicks()
        );
    }

    static BaritoneConfiguration baritoneConfiguration(
            ModuleRegistry modules
    ) {
        return new BaritoneConfiguration(
                integerValue(
                        modules,
                        "baritone_navigator",
                        "target_x",
                        0
                ),
                integerValue(
                        modules,
                        "baritone_navigator",
                        "target_y",
                        64
                ),
                integerValue(
                        modules,
                        "baritone_navigator",
                        "target_z",
                        0
                ),
                booleanValue(
                        modules,
                        "baritone_navigator",
                        "confirm_target",
                        false
                )
        );
    }

    static void clearBaritoneConfirmation(ModuleRegistry modules) {
        Setting<?> value = setting(
                modules,
                "baritone_navigator",
                "confirm_target"
        );
        if (value instanceof BooleanSetting confirmation) {
            confirmation.set(false);
        }
    }

    static void clearBaritoneSessionControls(ModuleRegistry modules) {
        clearBaritoneConfirmation(modules);
        modules.find("baritone_navigator")
                .ifPresent(module -> module.setEnabled(false));
    }

    record BaritoneConfiguration(
            int targetX,
            int targetY,
            int targetZ,
            boolean confirmed
    ) {
    }

    private static BooleanSetting bool(
            String id,
            String name,
            String description,
            boolean defaultValue
    ) {
        return new BooleanSetting(id, name, description, defaultValue);
    }

    private static IntegerSetting integer(
            String id,
            String name,
            String description,
            int defaultValue,
            int min,
            int max
    ) {
        return new IntegerSetting(
                id,
                name,
                description,
                defaultValue,
                min,
                max,
                1
        );
    }

    private static DoubleSetting decimal(
            String id,
            String name,
            String description,
            double defaultValue,
            double min,
            double max,
            double step
    ) {
        return new DoubleSetting(
                id,
                name,
                description,
                defaultValue,
                min,
                max,
                step
        );
    }

    private static StringSetting text(
            String id,
            String name,
            String description,
            String defaultValue,
            int maxLength
    ) {
        return new StringSetting(
                id,
                name,
                description,
                defaultValue,
                maxLength,
                value -> value.indexOf('\n') < 0
                        && value.indexOf('\r') < 0
                        && value.indexOf('\0') < 0,
                () -> true
        );
    }

    private static StringSetting color(
            String id,
            String name,
            String description,
            String defaultValue
    ) {
        return new StringSetting(
                id,
                name,
                description,
                defaultValue,
                8,
                value -> value.matches("[0-9A-Fa-f]{8}"),
                () -> true
        );
    }

    private static StringSetting visibleColor(
            String id,
            String name,
            String description,
            String defaultValue
    ) {
        return new StringSetting(
                id,
                name,
                description,
                defaultValue,
                8,
                value -> value.matches("[0-9A-Fa-f]{8}")
                        && !"00".equalsIgnoreCase(value.substring(0, 2)),
                () -> true
        );
    }

    private static boolean validOffhand(String value) {
        try {
            CombatInventoryAutomation26.OffhandItem.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static CombatInventoryAutomation26.OffhandItem offhandValue(
            String value,
            CombatInventoryAutomation26.OffhandItem fallback
    ) {
        try {
            return CombatInventoryAutomation26.OffhandItem.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static boolean booleanValue(
            ModuleRegistry modules,
            String module,
            String setting,
            boolean fallback
    ) {
        Setting<?> value = setting(modules, module, setting);
        return value instanceof BooleanSetting typed
                ? typed.value()
                : fallback;
    }

    private static boolean enabledValue(
            ModuleRegistry modules,
            String module
    ) {
        return modules.find(module)
                .map(RegisteredModule::enabled)
                .orElse(false);
    }

    private static int integerValue(
            ModuleRegistry modules,
            String module,
            String setting,
            int fallback
    ) {
        Setting<?> value = setting(modules, module, setting);
        return value instanceof IntegerSetting typed
                ? typed.value()
                : fallback;
    }

    private static double decimalValue(
            ModuleRegistry modules,
            String module,
            String setting,
            double fallback
    ) {
        Setting<?> value = setting(modules, module, setting);
        return value instanceof DoubleSetting typed
                ? typed.value()
                : fallback;
    }

    private static String stringValue(
            ModuleRegistry modules,
            String module,
            String setting,
            String fallback
    ) {
        Setting<?> value = setting(modules, module, setting);
        return value instanceof StringSetting typed
                ? typed.value()
                : fallback;
    }

    static int colorValue(
            ModuleRegistry modules,
            String module,
            String setting,
            int fallback
    ) {
        String encoded = stringValue(
                modules,
                module,
                setting,
                String.format(Locale.ROOT, "%08X", fallback)
        );
        try {
            return Integer.parseUnsignedInt(encoded, 16);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    static Set<String> commaSeparatedValue(
            ModuleRegistry modules,
            String module,
            String setting,
            Set<String> fallback,
            int maximumEntries
    ) {
        String encoded = stringValue(
                modules,
                module,
                setting,
                String.join(",", fallback)
        );
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : encoded.split(",")) {
            String normalized = part.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && normalized.length() <= 128) {
                values.add(normalized);
            }
            if (values.size() >= Math.max(0, maximumEntries)) {
                break;
            }
        }
        return Set.copyOf(values);
    }

    private static double minimumEnabledDecimal(
            ModuleRegistry modules,
            String setting,
            double fallback,
            EnabledModule... selections
    ) {
        double result = Double.POSITIVE_INFINITY;
        boolean found = false;
        for (EnabledModule selection : selections) {
            if (selection.enabled()) {
                result = Math.min(
                        result,
                        decimalValue(
                                modules,
                                selection.id(),
                                setting,
                                fallback
                        )
                );
                found = true;
            }
        }
        return found ? result : fallback;
    }

    private static double maximumEnabledDecimal(
            ModuleRegistry modules,
            String setting,
            double fallback,
            EnabledModule... selections
    ) {
        double result = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (EnabledModule selection : selections) {
            if (selection.enabled()) {
                result = Math.max(
                        result,
                        decimalValue(
                                modules,
                                selection.id(),
                                setting,
                                fallback
                        )
                );
                found = true;
            }
        }
        return found ? result : fallback;
    }

    private static int maximumEnabledInteger(
            ModuleRegistry modules,
            String setting,
            int fallback,
            EnabledModule... selections
    ) {
        int result = Integer.MIN_VALUE;
        boolean found = false;
        for (EnabledModule selection : selections) {
            if (selection.enabled()) {
                result = Math.max(
                        result,
                        integerValue(
                                modules,
                                selection.id(),
                                setting,
                                fallback
                        )
                );
                found = true;
            }
        }
        return found ? result : fallback;
    }

    private static Setting<?> setting(
            ModuleRegistry modules,
            String moduleId,
            String settingId
    ) {
        if (modules == null) {
            return null;
        }
        RegisteredModule module = modules.find(moduleId).orElse(null);
        if (module == null) {
            return null;
        }
        return module.settings().stream()
                .filter(candidate -> candidate.id().equals(settingId))
                .findFirst()
                .orElse(null);
    }

    record AttackConfiguration(
            double range,
            float cooldownThreshold,
            int minimumAttackTicks
    ) {
    }

    private record EnabledModule(String id, boolean enabled) {
    }
}
