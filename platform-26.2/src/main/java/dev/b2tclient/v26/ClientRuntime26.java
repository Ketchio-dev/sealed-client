package dev.b2tclient.v26;

import com.mojang.blaze3d.platform.InputConstants;
import dev.b2tclient.common.module.ModuleKeybindDispatcher;
import dev.b2tclient.common.module.ModuleRegistry;
import dev.b2tclient.common.module.RegisteredModule;
import dev.b2tclient.common.persistence.TickDebouncedSaveController;
import dev.b2tclient.common.profile.ClientProfile;
import dev.b2tclient.common.profile.ProfileBook;
import dev.b2tclient.common.social.FriendBook;
import dev.b2tclient.common.waypoint.WaypointBook;
import dev.b2tclient.v26.command.CommandManager26;
import dev.b2tclient.v26.config.ConfigStore26;
import dev.b2tclient.v26.config.PresetApplication26;
import dev.b2tclient.v26.gui.ClientScreen26;
import dev.b2tclient.v26.gui.ClientScreen26Model;
import dev.b2tclient.v26.hud.ClientHud26;
import dev.b2tclient.v26.hud.CombatTargetBridge26;
import dev.b2tclient.v26.hud.HudLayout26;
import dev.b2tclient.v26.hud.HudMetricsBridge26;
import dev.b2tclient.v26.automation.UtilityAutomation26;
import dev.b2tclient.v26.combat.CombatActionArbiter26;
import dev.b2tclient.v26.combat.CombatAttackAutomation26;
import dev.b2tclient.v26.combat.CombatBedAnchorAutomation26;
import dev.b2tclient.v26.combat.CombatBowAimAutomation26;
import dev.b2tclient.v26.combat.CombatCrystalMineAutomation26;
import dev.b2tclient.v26.combat.CombatDefensiveConstructionAutomation26;
import dev.b2tclient.v26.combat.CombatInventoryAutomation26;
import dev.b2tclient.v26.combat.CombatQuiverAutomation26;
import dev.b2tclient.v26.combat.CombatSiegeAutomation26;
import dev.b2tclient.v26.movement.ElytraControlAutomation26;
import dev.b2tclient.v26.movement.ElytraSwapAutomation26;
import dev.b2tclient.v26.movement.FallWaterMovementAutomation26;
import dev.b2tclient.v26.movement.MovementActionArbiter26;
import dev.b2tclient.v26.movement.MovementInputAutomation26;
import dev.b2tclient.v26.movement.MovementNetworkTracker26;
import dev.b2tclient.v26.movement.MovementSafetyPolicy26;
import dev.b2tclient.v26.movement.WalkMovementAutomation26;
import dev.b2tclient.v26.integration.BaritoneNavigator26;
import dev.b2tclient.v26.integration.OptionalBaritoneIntegration26;
import dev.b2tclient.v26.utility.AutoArmorAutomation26;
import dev.b2tclient.v26.utility.AutoCraftAutomation26;
import dev.b2tclient.v26.utility.AutoMendAutomation26;
import dev.b2tclient.v26.utility.ChestSwapAutomation26;
import dev.b2tclient.v26.utility.FastUseAutomation26;
import dev.b2tclient.v26.utility.InventoryManagerAutomation26;
import dev.b2tclient.v26.utility.ReplenishAutomation26;
import dev.b2tclient.v26.utility.UtilityActionArbiter26;
import dev.b2tclient.v26.visual.ChamsController26;
import dev.b2tclient.v26.visual.FreecamController26;
import dev.b2tclient.v26.visual.VisualOverlayConfiguration26;
import dev.b2tclient.v26.visual.VisualOverlayService26;
import dev.b2tclient.v26.visual.XRayController26;
import dev.b2tclient.v26.world.WorldTrackerRenderService26;
import dev.b2tclient.v26.world.WorldTrackerService26;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import dev.b2tclient.common.waypoint.Waypoint;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class ClientRuntime26 {
    private static final DateTimeFormatter DEATH_ID = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String COMBAT_ATTACK_OWNER = "combat_attack";
    private static final int COMBAT_ATTACK_PRIORITY = 70;
    private static final Set<CombatActionArbiter26.Channel> ATTACK_CHANNEL =
            Set.of(CombatActionArbiter26.Channel.ATTACK);
    private final ModuleRegistry modules = PlatformCapabilities26.createRegistry();
    private final FriendBook friends = new FriendBook();
    private final WaypointBook waypoints = new WaypointBook();
    private final ProfileBook profiles = new ProfileBook();
    private final ConfigStore26 configStore = ConfigStore26.defaultStore();
    private final TickDebouncedSaveController configSaves =
            new TickDebouncedSaveController(
                    8,
                    40,
                    this::saveImmediately
            );
    private final UtilityAutomation26 utilityAutomation = new UtilityAutomation26();
    private final CombatActionArbiter26 combatArbiter =
            new CombatActionArbiter26();
    private final CombatInventoryAutomation26 combatInventory =
            new CombatInventoryAutomation26();
    private final CombatAttackAutomation26 combatAttack =
            new CombatAttackAutomation26();
    private final CombatCrystalMineAutomation26 combatCrystalMine =
            new CombatCrystalMineAutomation26();
    private final CombatDefensiveConstructionAutomation26 combatConstruction =
            new CombatDefensiveConstructionAutomation26();
    private final CombatBedAnchorAutomation26 combatBedAnchor =
            new CombatBedAnchorAutomation26();
    private final CombatBowAimAutomation26 combatBowAim =
            new CombatBowAimAutomation26();
    private final CombatQuiverAutomation26 combatQuiver =
            new CombatQuiverAutomation26();
    private final CombatSiegeAutomation26 combatSiege =
            new CombatSiegeAutomation26();
    private final AutoArmorAutomation26 autoArmor =
            new AutoArmorAutomation26();
    private final ReplenishAutomation26 replenish =
            new ReplenishAutomation26();
    private final ChestSwapAutomation26 chestSwap =
            new ChestSwapAutomation26();
    private final InventoryManagerAutomation26 inventoryManager =
            new InventoryManagerAutomation26();
    private final VisualOverlayService26 visualOverlays =
            new VisualOverlayService26(
                    (uuid, name) ->
                            friends.findByUuid(uuid).isPresent()
                                    || friends.findByName(name).isPresent()
            );
    private final FreecamController26 freecam =
            new FreecamController26();
    private final XRayController26 xray =
            new XRayController26();
    private final ChamsController26 chams =
            new ChamsController26();
    private final WorldTrackerService26 worldTrackers =
            new WorldTrackerService26();
    private final WorldTrackerRenderService26 worldTrackerRenderer =
            new WorldTrackerRenderService26(worldTrackers);
    private final UtilityActionArbiter26 utilityArbiter =
            new UtilityActionArbiter26();
    private final ModuleKeybindDispatcher keybinds =
            new ModuleKeybindDispatcher();
    private final HudLayout26 hudLayout = new HudLayout26();
    private final PresetApplication26 presets = new PresetApplication26();
    private final CombatTargetBridge26 combatTarget = new CombatTargetBridge26();
    private final AutoMendAutomation26 autoMend =
            new AutoMendAutomation26();
    private final FastUseAutomation26 fastUse =
            new FastUseAutomation26();
    private final AutoCraftAutomation26 autoCraft =
            new AutoCraftAutomation26();
    private BaritoneNavigator26 baritone;
    private boolean baritoneModuleApplied;
    private Object baritoneConnectionIdentity;
    private Object baritoneLevelIdentity;
    private Object baritonePlayerIdentity;
    private boolean baritoneSessionUsable;
    private BaritoneNavigator26.NavigationTarget pendingBaritoneTarget;
    private boolean pendingBaritoneStop;
    private Object profileConnectionIdentity;
    private String profileServerKey = "";
    private final MovementNetworkTracker26 movementNetwork =
            new MovementNetworkTracker26();
    private final MovementActionArbiter26 movementArbiter =
            new MovementActionArbiter26();
    private final MovementSafetyPolicy26 movementSafety =
            new MovementSafetyPolicy26();
    private final WalkMovementAutomation26 walkMovement =
            new WalkMovementAutomation26();
    private final FallWaterMovementAutomation26 fallWaterMovement =
            new FallWaterMovementAutomation26();
    private final ElytraSwapAutomation26 elytraSwap =
            new ElytraSwapAutomation26();
    private final ElytraControlAutomation26 elytraControl =
            new ElytraControlAutomation26();
    private final MovementInputAutomation26 movementInput =
            new MovementInputAutomation26();
    private MovementSessionIdentity movementSessionIdentity;
    private volatile Connection activeMovementConnection;
    private boolean initialized;
    private boolean fullBrightApplied;
    private boolean autoWalkApplied;
    private boolean autoSprintApplied;
    private boolean antiAfkApplied;
    private boolean noViewBobApplied;
    private boolean clearWeatherApplied;
    private ClientLevel weatherLevel;
    private double previousGamma;
    private boolean previousViewBob;
    private float previousRain;
    private float previousThunder;
    private int antiAfkTicks;
    private int autoRespawnTicks;
    private boolean deathObserved;
    private String lastDeathLabel = "";

    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        baritone = OptionalBaritoneIntegration26.discover();
        ConfigStore26.LoadResult loadResult = configStore.load(modules, profiles, friends, waypoints, hudLayout);
        // A navigation confirmation is a one-session action, not a durable
        // command that may replay after a crash or profile restore.
        PlatformModuleSettings26.clearBaritoneConfirmation(modules);
        if (profiles.all().isEmpty()) {
            profiles.capture("default", "*", modules);
        }
        if (loadResult == ConfigStore26.LoadResult.CORRUPT) {
            B2TClient26.LOGGER.warn(
                    "The 26.2 config was invalid or oversized and was quarantined; safe defaults are active"
            );
        }

        KeyMapping.Category category = KeyMapping.Category.register(id("controls"));
        KeyMapping openGui = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.b2tclient.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                category
        ));

        HudElementRegistry.addLast(id("status"), new ClientHud26(this));
        new CommandManager26(this).initialize();
        ClientTickEvents.START_CLIENT_TICK.register(
                this::prepareFastUseForVanillaTick
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client, openGui));
        visualOverlays.initialize();
        worldTrackerRenderer.initialize();
        // A resource reload rebuilds vanilla's RenderType instances, so the
        // through-wall variants keyed by the old ones become unreachable
        // garbage that would otherwise sit in the cache until shutdown.
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return id("chams_render_type_cache");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        chams.clearRenderTypeCache();
                    }
                }
        );
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            releasePlatformState(client);
            chams.clearRenderTypeCache();
            save();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            releasePlatformState(client);
            save();
        });
    }

    private void tick(Minecraft client, KeyMapping openGui) {
        while (openGui.consumeClick()) {
            if (client.gui.screen() instanceof ClientScreen26) {
                client.gui.setScreen(null);
            } else {
                client.gui.setScreen(new ClientScreen26(this));
            }
        }

        runContainedTickStep(modules, "keybinds", List.of(),
                () -> tickModuleKeybinds(client));
        runContainedTickStep(modules, "server-profile", List.of(),
                () -> applyServerProfile(client));
        runContainedTickStep(modules, "full-bright", List.of("full_bright"),
                () -> applyFullBright(client));
        runContainedTickStep(modules, "view-bob", List.of("no_view_bob"),
                () -> applyNoViewBob(client));
        runContainedTickStep(modules, "weather", List.of("clear_weather"),
                () -> applyClearWeather(client));
        runContainedTickStep(
                modules,
                "visuals",
                List.of("player_esp", "tracers", "nametags", "storage_esp",
                        "hole_esp", "block_esp", "trajectories", "freecam",
                        "xray", "chams"),
                () -> applyVisualConfigurations(client)
        );
        runContainedTickStep(
                modules,
                "world-trackers",
                List.of("new_chunks", "logout_spots", "stash_finder"),
                () -> tickWorldTrackers(client)
        );
        runContainedTickStep(modules, "death-position", List.of("death_position"),
                () -> trackDeathPosition(client));

        boolean[] disconnected = {false};
        runContainedTickStep(
                modules,
                "auto-disconnect",
                List.of("auto_disconnect"),
                () -> disconnected[0] = applyAutoDisconnect(client)
        );
        if (disconnected[0]) {
            releasePlatformState(client);
            tickConfigSave();
            return;
        }

        runContainedTickStep(
                modules,
                "auto-reconnect",
                List.of("auto_reconnect"),
                () -> utilityAutomation.tickReconnect(
                        client,
                        enabled("auto_reconnect")
                )
        );
        runContainedTickStep(
                modules,
                "utility-release",
                List.of("auto_eat", "auto_tool"),
                () -> utilityAutomation.yieldOwnedActions(client)
        );
        runContainedTickStep(
                modules,
                "auto-mend-release",
                List.of("auto_mend"),
                () -> autoMend.yieldOwnedLease(client)
        );

        CombatAttackAutomation26.PreparedAttack[] attack = {null};
        runContainedTickStep(
                modules,
                "combat-prepare",
                combatModuleIds(),
                () -> attack[0] = prepareCombat(client)
        );
        boolean freecamRequested = enabled("freecam");
        runContainedTickStep(
                modules,
                "baritone",
                List.of("baritone_navigator"),
                () -> tickBaritone(client, freecamRequested)
        );
        runContainedTickStep(
                modules,
                "combat-execute",
                combatModuleIds(),
                () -> executeCombat(client, attack[0])
        );
        runContainedTickStep(
                modules,
                "combat-target",
                combatModuleIds(),
                () -> observeCombatTarget(client, attack[0])
        );
        runContainedTickStep(
                modules,
                "movement",
                movementModuleIds(),
                () -> tickMovement(client)
        );
        boolean freecamAllowed =
                !combatOwns(CombatActionArbiter26.Channel.MOVEMENT)
                        && !baritoneOwnsMovement();
        runContainedTickStep(
                modules,
                "freecam",
                List.of("freecam"),
                () -> freecam.tick(client, freecamRequested, freecamAllowed)
        );
        runContainedTickStep(
                modules,
                "utility",
                utilityModuleIds(),
                () -> tickUtility(client)
        );
        runContainedTickStep(
                modules,
                "auto-respawn",
                List.of("auto_respawn"),
                () -> applyAutoRespawn(client)
        );
        runContainedTickStep(modules, "config-save", List.of(), this::tickConfigSave);
    }

    private List<String> combatModuleIds() {
        return modules.byCategory().getOrDefault(
                dev.b2tclient.common.module.ModuleCategory.COMBAT,
                List.of()
        ).stream().map(module -> module.descriptor().id()).toList();
    }

    private List<String> movementModuleIds() {
        return modules.byCategory().getOrDefault(
                dev.b2tclient.common.module.ModuleCategory.MOVEMENT,
                List.of()
        ).stream().map(module -> module.descriptor().id()).toList();
    }

    private static List<String> utilityModuleIds() {
        return List.of(
                "auto_eat", "auto_tool", "auto_armor", "replenish",
                "chest_swap", "auto_mend", "fast_use", "inventory_manager",
                "auto_craft"
        );
    }

    static void runContainedTickStep(
            ModuleRegistry modules,
            String subsystem,
            List<String> moduleIds,
            Runnable step
    ) {
        runContainedTickStep(modules, subsystem, moduleIds, step, failure ->
                B2TClient26.LOGGER.error(
                        "Disabling 26.2 module {} after {} tick failure",
                        failure.moduleId(),
                        failure.subsystem(),
                        failure.exception()
                )
        );
    }

    static void runContainedTickStep(
            ModuleRegistry modules,
            String subsystem,
            List<String> moduleIds,
            Runnable step,
            Consumer<TickFailure> reporter
    ) {
        try {
            step.run();
        } catch (RuntimeException exception) {
            List<RegisteredModule> active = moduleIds.stream()
                    .map(modules::find)
                    .flatMap(java.util.Optional::stream)
                    .filter(RegisteredModule::enabled)
                    .toList();
            if (active.isEmpty()) {
                reporter.accept(new TickFailure(subsystem, "(none)", exception));
                return;
            }
            for (RegisteredModule module : active) {
                String moduleId = module.descriptor().id();
                try {
                    module.setEnabled(false);
                } catch (RuntimeException disableFailure) {
                    exception.addSuppressed(disableFailure);
                }
                reporter.accept(new TickFailure(subsystem, moduleId, exception));
            }
        }
    }

    record TickFailure(
            String subsystem,
            String moduleId,
            RuntimeException exception
    ) {
    }

    /**
     * Applies user keybinds by polling GLFW and toggling on the rising edge.
     *
     * <p>Polling rather than hooking raw key callbacks keeps the binding path
     * free of an extra Mixin and guarantees the toggle happens on the client
     * thread, in the same tick phase as every other module decision. Any open
     * screen blocks dispatch so typing never toggles a module.</p>
     */
    private void tickModuleKeybinds(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        var window = client.getWindow();
        boolean blocked = client.gui == null || client.gui.screen() != null;
        List<RegisteredModule> triggered = keybinds.pressedThisTick(
                modules.all(),
                keyCode -> InputConstants.isKeyDown(window, keyCode),
                blocked
        );
        if (triggered.isEmpty()) {
            return;
        }
        for (RegisteredModule module : triggered) {
            if (!ClientScreen26Model.runtimeAvailable(
                    module,
                    baritone != null && baritone.available()
            )) {
                continue;
            }
            module.toggle();
        }
        requestSave();
    }

    private void applyServerProfile(Minecraft client) {
        Object connection = client == null || client.getConnection() == null
                ? null
                : client.getConnection().getConnection();
        if (connection == null || !client.getConnection()
                .getConnection().isConnected()) {
            profileConnectionIdentity = null;
            profileServerKey = "";
            return;
        }
        String server = serverKey(client);
        if (profileConnectionIdentity == connection
                && profileServerKey.equals(server)) {
            return;
        }
        profileConnectionIdentity = connection;
        profileServerKey = server;
        if (profiles.activateBestMatchForServer(server, modules)) {
            presets.clear();
            clearBaritoneConfirmation();
            requestSave();
        }
    }

    private void tickConfigSave() {
        TickDebouncedSaveController.TickResult result = configSaves.tick();
        if (result == TickDebouncedSaveController.TickResult.FAILED) {
            configSaves.lastFailure().ifPresent(exception ->
                    B2TClient26.LOGGER.error(
                            "Could not atomically save the 26.2 config",
                            exception
                    )
            );
        }
    }

    private void applyVisualConfigurations(Minecraft client) {
        VisualOverlayConfiguration26 overlayConfiguration =
                PlatformModuleSettings26.visualOverlayConfiguration(modules);
        if (!overlayConfiguration.equals(visualOverlays.configuration())) {
            visualOverlays.setConfiguration(overlayConfiguration);
        }
        FreecamController26.Configuration freecamConfiguration =
                PlatformModuleSettings26.freecamConfiguration(modules);
        if (!freecamConfiguration.equals(freecam.configuration())) {
            freecam.setConfiguration(freecamConfiguration);
        }
        XRayController26.Configuration xrayConfiguration =
                PlatformModuleSettings26.xrayConfiguration(modules);
        if (!xrayConfiguration.equals(xray.configuration())) {
            xray.setConfiguration(xrayConfiguration);
        }
        ChamsController26.Configuration chamsConfiguration =
                PlatformModuleSettings26.chamsConfiguration(modules);
        if (!chamsConfiguration.equals(chams.configuration())) {
            chams.setConfiguration(chamsConfiguration);
        }
        xray.tick(client, enabled("xray"));
        chams.tick(client, enabled("chams"));
    }

    private void tickWorldTrackers(Minecraft client) {
        if (client == null
                || client.player == null
                || !client.player.isAlive()
                || client.player.isDeadOrDying()
                || client.player.isSpectator()) {
            worldTrackers.release();
            worldTrackerRenderer.reset();
            return;
        }
        WorldTrackerService26.Configuration trackerConfiguration =
                PlatformModuleSettings26.worldTrackerConfiguration(modules);
        if (!trackerConfiguration.equals(worldTrackers.configuration())) {
            worldTrackers.setConfiguration(trackerConfiguration);
        }
        WorldTrackerRenderService26.Configuration renderConfiguration =
                PlatformModuleSettings26.worldTrackerRenderConfiguration(
                        modules
                );
        if (!renderConfiguration.equals(
                worldTrackerRenderer.configuration()
        )) {
            worldTrackerRenderer.setConfiguration(renderConfiguration);
        }
        worldTrackers.tick(
                client,
                new WorldTrackerService26.ModuleState(
                        enabled("new_chunks"),
                        enabled("logout_spots"),
                        enabled("stash_finder")
                )
        );
    }

    private CombatAttackAutomation26.PreparedAttack prepareCombat(
            Minecraft client
    ) {
        boolean sessionActive = client != null
                && client.level != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
        boolean playerPresent = client != null && client.player != null;
        CombatActionArbiter26.SafetyContext safety =
                new CombatActionArbiter26.SafetyContext(
                        sessionActive,
                        playerPresent,
                        playerPresent
                                && client.player.isAlive()
                                && !client.player.isDeadOrDying()
                                && !client.player.isSpectator(),
                        client != null && client.gui.screen() == null
                );
        combatArbiter.beginTick(safety);
        if (safety.block() != CombatActionArbiter26.SafetyBlock.NONE) {
            releaseCombat(client);
            return null;
        }

        boolean autoTotemEnabled = enabled("auto_totem");
        boolean triggerBotEnabled = enabled("trigger_bot");
        boolean surroundEnabled = enabled("surround");
        boolean holeFillEnabled = enabled("hole_fill");
        boolean selfTrapEnabled = enabled("self_trap");
        boolean autoTrapEnabled = enabled("auto_trap");
        boolean burrowEnabled = enabled("burrow");
        boolean anchorEnabled = enabled("anchor_aura");
        boolean bedEnabled = enabled("bed_aura");
        boolean quiverEnabled = enabled("quiver");
        boolean cityBreakerEnabled = enabled("city_breaker");
        boolean pistonCrystalEnabled = enabled("piston_crystal");
        CombatInventoryAutomation26.Configuration inventoryConfiguration =
                PlatformModuleSettings26.inventoryConfiguration(
                        modules,
                        autoTotemEnabled
                );
        if (!inventoryConfiguration.equals(combatInventory.configuration())) {
            combatInventory.setConfiguration(inventoryConfiguration);
        }
        CombatCrystalMineAutomation26.Configuration crystalConfiguration =
                PlatformModuleSettings26.crystalMineConfiguration(modules);
        if (!crystalConfiguration.equals(
                combatCrystalMine.configuration()
        )) {
            combatCrystalMine.setConfiguration(crystalConfiguration);
        }
        CombatDefensiveConstructionAutomation26.Configuration
                constructionConfiguration =
                PlatformModuleSettings26.defensiveConstructionConfiguration(
                        modules,
                        surroundEnabled,
                        holeFillEnabled,
                        selfTrapEnabled,
                        autoTrapEnabled,
                        burrowEnabled
                );
        if (!constructionConfiguration.equals(
                combatConstruction.configuration()
        )) {
            combatConstruction.setConfiguration(constructionConfiguration);
        }
        CombatBedAnchorAutomation26.Configuration anchorConfiguration =
                PlatformModuleSettings26.anchorConfiguration(modules);
        if (!anchorConfiguration.equals(
                combatBedAnchor.anchorConfiguration()
        )) {
            combatBedAnchor.setAnchorConfiguration(anchorConfiguration);
        }
        CombatBedAnchorAutomation26.Configuration bedConfiguration =
                PlatformModuleSettings26.bedConfiguration(modules);
        if (!bedConfiguration.equals(
                combatBedAnchor.bedConfiguration()
        )) {
            combatBedAnchor.setBedConfiguration(bedConfiguration);
        }
        CombatBowAimAutomation26.Configuration bowAimConfiguration =
                PlatformModuleSettings26.bowAimConfiguration(modules);
        if (!bowAimConfiguration.equals(combatBowAim.configuration())) {
            combatBowAim.setConfiguration(bowAimConfiguration);
        }
        CombatQuiverAutomation26.Configuration quiverConfiguration =
                PlatformModuleSettings26.quiverConfiguration(modules);
        if (!quiverConfiguration.equals(combatQuiver.configuration())) {
            combatQuiver.setConfiguration(quiverConfiguration);
        }
        CombatSiegeAutomation26.ModeConfiguration siegeConfiguration =
                new CombatSiegeAutomation26.ModeConfiguration(
                        PlatformModuleSettings26
                                .cityBreakerConfiguration(modules),
                        PlatformModuleSettings26
                                .pistonCrystalConfiguration(modules)
                );
        if (!siegeConfiguration.equals(combatSiege.modeConfiguration())) {
            combatSiege.setModeConfiguration(siegeConfiguration);
        }
        applyInventoryUtilityConfigurations();
        combatInventory.submit(
                client,
                autoTotemEnabled,
                enabled("offhand"),
                enabled("anti_weakness"),
                enabled("auto_weapon"),
                combatArbiter
        );
        combatCrystalMine.submit(
                client,
                friends,
                enabled("auto_crystal"),
                enabled("auto_mine"),
                combatArbiter
        );
        combatConstruction.submit(
                client,
                friends,
                surroundEnabled,
                holeFillEnabled,
                selfTrapEnabled,
                autoTrapEnabled,
                burrowEnabled,
                combatArbiter
        );
        combatBedAnchor.submit(
                client,
                friends,
                anchorEnabled,
                bedEnabled,
                combatArbiter
        );
        combatBowAim.submit(
                client,
                enabled("bow_aim"),
                (uuid, name) ->
                        friends.findByUuid(uuid).isPresent()
                                || friends.findByName(name).isPresent(),
                combatArbiter
        );
        combatQuiver.submit(
                client,
                quiverEnabled,
                combatArbiter
        );
        combatSiege.submit(
                client,
                friends,
                cityBreakerEnabled,
                pistonCrystalEnabled,
                combatArbiter
        );
        autoArmor.submit(
                client,
                enabled("auto_armor"),
                false,
                combatArbiter
        );
        replenish.submit(
                client,
                enabled("replenish"),
                false,
                combatArbiter
        );
        chestSwap.submit(
                client,
                enabled("chest_swap"),
                false,
                combatArbiter
        );
        inventoryManager.submit(
                client,
                enabled("inventory_manager"),
                false,
                combatArbiter
        );

        PlatformModuleSettings26.AttackConfiguration triggerConfiguration =
                PlatformModuleSettings26.attackConfiguration(
                        modules,
                        true
                );
        PlatformModuleSettings26.AttackConfiguration auraConfiguration =
                PlatformModuleSettings26.attackConfiguration(
                        modules,
                        false
                );
        CombatAttackAutomation26.PreparedAttack attack =
                combatAttack.prepare(
                        client,
                        triggerBotEnabled,
                        enabled("criticals"),
                        enabled("kill_aura"),
                        (uuid, name) ->
                        friends.findByUuid(uuid).isPresent()
                                        || friends.findByName(name).isPresent(),
                        CombatAttackAutomation26.TargetMode.PLAYERS,
                        new CombatAttackAutomation26.AttackSettings(
                                triggerConfiguration.range(),
                                triggerConfiguration.cooldownThreshold(),
                                triggerConfiguration.minimumAttackTicks()
                        ),
                        new CombatAttackAutomation26.AttackSettings(
                                auraConfiguration.range(),
                                auraConfiguration.cooldownThreshold(),
                                auraConfiguration.minimumAttackTicks()
                        )
                );
        if (attack.requiresAttackChannel()) {
            combatArbiter.submit(
                    COMBAT_ATTACK_OWNER,
                    COMBAT_ATTACK_PRIORITY,
                    ATTACK_CHANNEL
            );
        }

        combatArbiter.resolve();
        return attack;
    }

    /**
     * Publishes the target the combat modules actually acted on this tick.
     *
     * <p>The attack selector wins over AutoCrystal because it is the module
     * that swings; AutoCrystal's pick is shown only when nothing is being
     * attacked directly.</p>
     */
    private void observeCombatTarget(
            Minecraft client,
            CombatAttackAutomation26.PreparedAttack attack
    ) {
        if (client == null || client.player == null) {
            combatTarget.clear();
            return;
        }
        int tick = client.player.tickCount;
        if (attack != null && attack.requested()) {
            combatTarget.observe(
                    attack.targetEntityId(),
                    attack.source() == CombatAttackAutomation26.AttackSource.TRIGGER_BOT
                            ? CombatTargetBridge26.Source.TRIGGER_BOT
                            : CombatTargetBridge26.Source.KILL_AURA,
                    tick
            );
            return;
        }
        int crystalTarget = enabled("auto_crystal")
                ? combatCrystalMine.lastTargetEntityId()
                : -1;
        combatTarget.observe(
                crystalTarget,
                CombatTargetBridge26.Source.AUTO_CRYSTAL,
                tick
        );
    }

    private void executeCombat(
            Minecraft client,
            CombatAttackAutomation26.PreparedAttack attack
    ) {
        if (attack == null) {
            return;
        }
        combatInventory.execute(client, combatArbiter);
        combatCrystalMine.execute(client, friends, combatArbiter);
        combatConstruction.execute(client, friends, combatArbiter);
        combatBedAnchor.execute(client, friends, combatArbiter);
        combatBowAim.execute(client, combatArbiter);
        combatQuiver.execute(client, combatArbiter);
        combatSiege.execute(client, friends, combatArbiter);
        autoArmor.execute(client, combatArbiter);
        replenish.execute(client, combatArbiter);
        chestSwap.execute(client, combatArbiter);
        inventoryManager.execute(client, combatArbiter);
        if (combatArbiter.ownsAll(COMBAT_ATTACK_OWNER, ATTACK_CHANNEL)) {
            combatAttack.execute(client, attack);
        }
    }

    private void applyInventoryUtilityConfigurations() {
        AutoArmorAutomation26.Configuration armor =
                PlatformModuleSettings26.autoArmorConfiguration(modules);
        if (!armor.equals(autoArmor.configuration())) {
            autoArmor.setConfiguration(armor);
        }
        ReplenishAutomation26.Configuration replenishConfiguration =
                PlatformModuleSettings26.replenishConfiguration(modules);
        if (!replenishConfiguration.equals(replenish.configuration())) {
            replenish.setConfiguration(replenishConfiguration);
        }
        ChestSwapAutomation26.Configuration chest =
                PlatformModuleSettings26.chestSwapConfiguration(modules);
        if (!chest.equals(chestSwap.configuration())) {
            chestSwap.setConfiguration(chest);
        }
        InventoryManagerAutomation26.Configuration inventory =
                PlatformModuleSettings26.inventoryManagerConfiguration(
                        modules
                );
        if (!inventory.equals(inventoryManager.configuration())) {
            inventoryManager.setConfiguration(inventory);
        }
    }

    private void tickUtility(Minecraft client) {
        boolean sessionActive = client != null
                && client.level != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
        LocalPlayer player = client == null ? null : client.player;
        boolean playerPresent = player != null;
        boolean playerAlive = playerPresent
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isSpectator();
        RuntimeArbitrationPolicy26.UtilityReadiness readiness =
                RuntimeArbitrationPolicy26.utilityReadiness(
                        sessionActive,
                        movementSafety.decision().networkReady(),
                        freecam.ownsMovement(),
                        baritoneOwnsMovement()
                );
        utilityArbiter.beginTick(
                new UtilityActionArbiter26.SafetyContext(
                        sessionActive,
                        playerPresent,
                        playerAlive,
                        readiness.transportReady()
                ),
                externalUtilityReservations()
        );

        applyUseCraftConfigurations();
        utilityAutomation.submit(
                client,
                enabled("auto_eat"),
                enabled("auto_tool"),
                utilityArbiter
        );
        autoMend.submit(
                client,
                enabled("auto_mend"),
                readiness.movementSensitiveReady(),
                utilityArbiter
        );
        fastUse.submit(
                client,
                enabled("fast_use"),
                readiness.movementSensitiveReady(),
                utilityArbiter
        );
        autoCraft.submit(
                client,
                enabled("auto_craft"),
                readiness.transportReady(),
                utilityArbiter
        );
        if (utilityArbiter.snapshot().safetyBlock()
                == UtilityActionArbiter26.SafetyBlock.NONE) {
            utilityArbiter.resolve();
        }
        utilityAutomation.execute(
                client,
                enabled("auto_eat"),
                enabled("auto_tool"),
                utilityArbiter
        );
        autoMend.execute(client, utilityArbiter);
        fastUse.execute(client, utilityArbiter);
        autoCraft.execute(client, utilityArbiter);
    }

    private void tickBaritone(
            Minecraft client,
            boolean freecamRequested
    ) {
        if (baritone == null) {
            return;
        }

        boolean sessionUsable = client != null
                && client.level != null
                && client.player != null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator()
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
        if (!sessionUsable) {
            if (baritoneSessionUsable || baritone.movementReserved()) {
                baritone.resetSession();
            }
            clearBaritoneSessionIdentity();
            return;
        }

        Object connection = client.getConnection().getConnection();
        boolean sessionChanged =
                baritoneConnectionIdentity != connection
                        || baritoneLevelIdentity != client.level
                        || baritonePlayerIdentity != client.player;
        if (sessionChanged) {
            if (baritoneSessionUsable || baritone.movementReserved()) {
                baritone.resetSession();
            }
            baritoneConnectionIdentity = connection;
            baritoneLevelIdentity = client.level;
            baritonePlayerIdentity = client.player;
            baritoneSessionUsable = true;
            baritoneModuleApplied = false;
            return;
        }
        baritoneSessionUsable = true;

        if (pendingBaritoneStop) {
            pendingBaritoneStop = false;
            pendingBaritoneTarget = null;
            PlatformModuleSettings26.clearBaritoneConfirmation(modules);
            baritone.stop();
            modules.find("baritone_navigator")
                    .ifPresent(module -> module.setEnabled(false));
            baritoneModuleApplied = false;
            save();
            return;
        }

        PlatformModuleSettings26.BaritoneConfiguration configuration =
                PlatformModuleSettings26.baritoneConfiguration(modules);
        if (configuration.confirmed()) {
            pendingBaritoneTarget =
                    new BaritoneNavigator26.NavigationTarget(
                            configuration.targetX(),
                            configuration.targetY(),
                            configuration.targetZ()
                    );
            PlatformModuleSettings26.clearBaritoneConfirmation(modules);
            save();
        }

        boolean requested = enabled("baritone_navigator")
                || pendingBaritoneTarget != null;
        if (!requested) {
            if (baritoneModuleApplied || baritone.movementReserved()) {
                baritone.releaseOwnedNavigation();
            }
            baritoneModuleApplied = false;
            return;
        }

        if (!baritone.available()) {
            pendingBaritoneTarget = null;
            modules.find("baritone_navigator")
                    .ifPresent(module -> module.setEnabled(false));
            baritoneModuleApplied = false;
            save();
            return;
        }

        boolean blocked = RuntimeArbitrationPolicy26.baritoneBlocked(
                !combatArbiter.snapshot().channelGrants().isEmpty(),
                freecamRequested,
                client.gui.screen() != null
        );
        if (pendingBaritoneTarget != null && !blocked) {
            BaritoneNavigator26.NavigationTarget target =
                    pendingBaritoneTarget;
            pendingBaritoneTarget = null;
            BaritoneNavigator26.NavigationResult result = baritone.goTo(
                    target.x(),
                    target.y(),
                    target.z()
            );
            if (!result.success()) {
                B2TClient26.LOGGER.warn(
                        "Baritone navigation was not started: {}",
                        result.message()
                );
                modules.find("baritone_navigator")
                        .ifPresent(module -> module.setEnabled(false));
                baritoneModuleApplied = false;
            } else {
                modules.find("baritone_navigator")
                        .ifPresent(module -> module.setEnabled(true));
                baritoneModuleApplied = true;
            }
            save();
            return;
        }

        BaritoneNavigator26.NavigationStatus status = baritone.status();
        if (status.ownedByB2T()) {
            if (blocked
                    && status.state()
                    != BaritoneNavigator26.NavigationState.PAUSED) {
                baritone.pause();
                return;
            } else if (!blocked
                    && status.state()
                    == BaritoneNavigator26.NavigationState.PAUSED) {
                baritone.resume();
                return;
            }
        }
        baritone.tick();
        status = baritone.status();
        baritoneModuleApplied = status.ownedByB2T();
        if (RuntimeArbitrationPolicy26.baritoneModuleShouldDeactivate(
                status.ownedByB2T(),
                pendingBaritoneTarget != null,
                status.state()
        )) {
            modules.find("baritone_navigator")
                    .filter(RegisteredModule::enabled)
                    .ifPresent(module -> {
                        module.setEnabled(false);
                        save();
                    });
        }
    }

    private void applyUseCraftConfigurations() {
        var mend = PlatformModuleSettings26.autoMendConfiguration(modules);
        if (!mend.equals(autoMend.configuration())) {
            autoMend.setConfiguration(mend);
        }
        applyFastUseConfiguration();
        AutoCraftAutomation26.Configuration craft =
                PlatformModuleSettings26.autoCraftConfiguration(modules);
        if (!craft.equals(autoCraft.configuration())) {
            autoCraft.setConfiguration(craft);
        }
    }

    private void applyFastUseConfiguration() {
        FastUseAutomation26.Configuration fast =
                PlatformModuleSettings26.fastUseConfiguration(modules);
        if (!fast.equals(fastUse.configuration())) {
            fastUse.setConfiguration(fast);
        }
    }

    private void prepareFastUseForVanillaTick(Minecraft client) {
        applyFastUseConfiguration();
        boolean sessionActive = client != null
                && client.level != null
                && client.player != null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator()
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
        boolean utilitySafetyReady = sessionActive
                && movementSafety.decision().networkReady()
                && !freecam.ownsMovement()
                && !baritoneOwnsMovement()
                && combatArbiter.snapshot().channelGrants().isEmpty();
        fastUse.prepareVanillaTick(
                client,
                enabled("fast_use"),
                utilitySafetyReady
        );
    }

    private Set<UtilityActionArbiter26.Channel>
            externalUtilityReservations() {
        EnumSet<UtilityActionArbiter26.Channel> reserved =
                EnumSet.noneOf(UtilityActionArbiter26.Channel.class);
        if (baritoneOwnsMovement()) {
            reserved.addAll(EnumSet.allOf(
                    UtilityActionArbiter26.Channel.class
            ));
        }
        if (combatOwns(CombatActionArbiter26.Channel.USE)) {
            reserved.add(UtilityActionArbiter26.Channel.USE);
        }
        if (combatOwns(CombatActionArbiter26.Channel.HOTBAR)) {
            reserved.add(UtilityActionArbiter26.Channel.HOTBAR);
        }
        if (combatOwns(CombatActionArbiter26.Channel.INVENTORY)) {
            reserved.add(UtilityActionArbiter26.Channel.INVENTORY);
        }
        if (combatOwns(CombatActionArbiter26.Channel.ROTATION)) {
            reserved.add(UtilityActionArbiter26.Channel.ROTATION);
        }
        var movementGrants = movementArbiter.snapshot().channelGrants();
        if (movementGrants.containsKey(
                MovementActionArbiter26.Channel.HOTBAR
        )) {
            reserved.add(UtilityActionArbiter26.Channel.HOTBAR);
        }
        if (movementGrants.containsKey(
                MovementActionArbiter26.Channel.INVENTORY
        )) {
            reserved.add(UtilityActionArbiter26.Channel.INVENTORY);
        }
        if (movementGrants.containsKey(
                MovementActionArbiter26.Channel.ROTATION
        )) {
            reserved.add(UtilityActionArbiter26.Channel.ROTATION);
        }
        return Set.copyOf(reserved);
    }

    private void tickMovement(Minecraft client) {
        boolean sessionActive = client != null
                && client.level != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
        LocalPlayer player = client == null ? null : client.player;
        boolean playerPresent = player != null;
        boolean playerAlive = playerPresent
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isSpectator();
        boolean screenClear = client != null && client.gui.screen() == null;
        boolean usable = sessionActive && playerAlive;
        activeMovementConnection = sessionActive
                ? client.getConnection().getConnection()
                : null;
        MovementNetworkTracker26.Snapshot network =
                movementNetwork.snapshot();
        MovementSafetyPolicy26.Decision safety =
                movementSafety.observe(
                        new MovementSafetyPolicy26.Observation(
                                movementSessionIdentity(client, usable),
                                playerPresent ? player.getX() : 0.0,
                                playerPresent ? player.getY() : 0.0,
                                playerPresent ? player.getZ() : 0.0,
                                latency(client),
                                usable,
                                network.correctionSequence(),
                                network.inboundSilenceMillis()
                        )
                );

        boolean combatMovementBusy =
                combatOwns(CombatActionArbiter26.Channel.MOVEMENT)
                        || enabled("freecam")
                        || freecam.ownsMovement()
                        || baritoneOwnsMovement();
        MovementSafetyPolicy26.Decision movementDecision =
                combatMovementBusy ? pausedDecision() : safety;
        boolean canDriveMovementKeys = playerAlive
                && screenClear
                && movementDecision.state()
                == MovementSafetyPolicy26.State.ACTIVE;
        autoWalkApplied = driveKey(
                client.options.keyUp,
                canDriveMovementKeys && enabled("auto_walk"),
                autoWalkApplied
        );
        autoSprintApplied = driveKey(
                client.options.keySprint,
                canDriveMovementKeys && enabled("auto_sprint"),
                autoSprintApplied
        );
        applyAntiAfk(client, canDriveMovementKeys);
        movementArbiter.beginTick(new MovementActionArbiter26.SafetyContext(
                sessionActive,
                playerPresent,
                playerAlive,
                screenClear,
                movementDecision.networkReady()
        ));

        applyMovementConfigurations();
        walkMovement.submit(
                client,
                enabled("safe_walk"),
                enabled("auto_center"),
                enabled("hole_snap"),
                enabled("step"),
                movementDecision,
                movementArbiter
        );
        fallWaterMovement.submit(
                client,
                enabled("no_fall"),
                enabled("fast_swim"),
                enabled("jesus"),
                movementDecision,
                movementArbiter
        );

        boolean inventoryBusy =
                combatOwns(CombatActionArbiter26.Channel.HOTBAR)
                || combatOwns(CombatActionArbiter26.Channel.INVENTORY);
        elytraSwap.submit(
                client,
                enabled("elytra_swap"),
                inventoryBusy ? pausedDecision() : movementDecision,
                movementArbiter
        );
        boolean combatRotationBusy =
                combatOwns(CombatActionArbiter26.Channel.ROTATION);
        elytraControl.submit(
                client,
                enabled("elytra_control"),
                !combatRotationBusy,
                movementDecision,
                movementArbiter
        );
        movementInput.submit(
                client,
                enabled("ground_speed"),
                enabled("no_slow"),
                enabled("no_rotate"),
                movementDecision,
                movementArbiter
        );

        if (movementArbiter.snapshot().safetyBlock()
                == MovementActionArbiter26.SafetyBlock.NONE) {
            movementArbiter.resolve();
        }
        WalkMovementAutomation26.Execution walk =
                walkMovement.execute(client, movementArbiter);
        FallWaterMovementAutomation26.Execution fallWater =
                fallWaterMovement.execute(client, movementArbiter);
        elytraSwap.execute(client, movementArbiter);
        boolean elytraApplied =
                elytraControl.execute(client, movementArbiter);
        MovementInputAutomation26.Execution input =
                movementInput.execute(client, movementArbiter);
        boolean velocityApplied = walk.horizontal().isPresent()
                || fallWater.velocityApplied()
                || (elytraApplied && elytraControl.lastVelocityApplied())
                || input.applied();
        if (velocityApplied && client.player != null) {
            var velocity = client.player.getDeltaMovement();
            movementSafety.recordApplied(
                    velocity.x,
                    velocity.y,
                    velocity.z
            );
        }
    }

    private void applyMovementConfigurations() {
        WalkMovementAutomation26.Configuration walk =
                PlatformModuleSettings26.walkMovementConfiguration(modules);
        if (!walk.equals(walkMovement.configuration())) {
            walkMovement.setConfiguration(walk);
        }
        FallWaterMovementAutomation26.Configuration fallWater =
                PlatformModuleSettings26.fallWaterMovementConfiguration(
                        modules
                );
        if (!fallWater.equals(fallWaterMovement.configuration())) {
            fallWaterMovement.setConfiguration(fallWater);
        }
        ElytraSwapAutomation26.Configuration swap =
                PlatformModuleSettings26.elytraSwapConfiguration(modules);
        if (!swap.equals(elytraSwap.configuration())) {
            elytraSwap.setConfiguration(swap);
        }
        ElytraControlAutomation26.Configuration control =
                PlatformModuleSettings26.elytraControlConfiguration(modules);
        if (!control.equals(elytraControl.configuration())) {
            elytraControl.setConfiguration(control);
        }
        MovementInputAutomation26.Configuration input =
                PlatformModuleSettings26.movementInputConfiguration(modules);
        if (!input.equals(movementInput.configuration())) {
            movementInput.setConfiguration(input);
        }
    }

    private MovementSessionIdentity movementSessionIdentity(
            Minecraft client,
            boolean usable
    ) {
        if (!usable
                || client == null
                || client.player == null
                || client.level == null
                || client.getConnection() == null) {
            movementSessionIdentity = null;
            return null;
        }
        Object connection = client.getConnection().getConnection();
        if (movementSessionIdentity == null
                || movementSessionIdentity.connection() != connection
                || movementSessionIdentity.level() != client.level
                || movementSessionIdentity.player() != client.player) {
            movementSessionIdentity = new MovementSessionIdentity(
                    connection,
                    client.level,
                    client.player
            );
        }
        return movementSessionIdentity;
    }

    private static int latency(Minecraft client) {
        if (client == null
                || client.player == null
                || client.getConnection() == null) {
            return -1;
        }
        var info = client.getConnection().getPlayerInfo(
                client.player.getUUID()
        );
        return info == null ? -1 : Math.max(-1, info.getLatency());
    }

    private boolean combatOwns(CombatActionArbiter26.Channel channel) {
        return combatArbiter.snapshot().channelGrants().containsKey(channel);
    }

    private boolean baritoneOwnsMovement() {
        return baritone != null && baritone.movementReserved();
    }

    private void clearBaritoneSessionIdentity() {
        baritoneConnectionIdentity = null;
        baritoneLevelIdentity = null;
        baritonePlayerIdentity = null;
        baritoneSessionUsable = false;
        baritoneModuleApplied = false;
    }

    private static MovementSafetyPolicy26.Decision pausedDecision() {
        return new MovementSafetyPolicy26.Decision(
                MovementSafetyPolicy26.State.PAUSED,
                MovementSafetyPolicy26.Reason.UNUSABLE,
                0.0,
                0,
                0,
                0
        );
    }

    private void applyFullBright(Minecraft client) {
        boolean requested = enabled("full_bright");
        if (requested && !fullBrightApplied) {
            previousGamma = client.options.gamma().get();
            client.options.gamma().set(1.0);
            fullBrightApplied = true;
        } else if (!requested && fullBrightApplied) {
            client.options.gamma().set(previousGamma);
            fullBrightApplied = false;
        }
    }

    private static boolean driveKey(KeyMapping key, boolean requested, boolean previouslyApplied) {
        if (requested) {
            key.setDown(true);
            return true;
        }
        if (previouslyApplied) {
            key.setDown(false);
        }
        return false;
    }

    private void applyNoViewBob(Minecraft client) {
        boolean requested = enabled("no_view_bob");
        if (requested && !noViewBobApplied) {
            previousViewBob = client.options.bobView().get();
            client.options.bobView().set(false);
            noViewBobApplied = true;
        } else if (!requested && noViewBobApplied) {
            client.options.bobView().set(previousViewBob);
            noViewBobApplied = false;
        }
    }

    private boolean applyAutoDisconnect(Minecraft client) {
        if (!enabled("auto_disconnect") || client.player == null || client.getConnection() == null) {
            return false;
        }
        if (client.player.getHealth() + client.player.getAbsorptionAmount() > 6.0F) {
            return false;
        }
        modules.find("auto_disconnect").orElseThrow().setEnabled(false);
        client.getConnection().getConnection().disconnect(
                Component.literal("B2T Auto Disconnect: health reached 3 hearts")
        );
        return true;
    }

    private void applyAutoRespawn(Minecraft client) {
        if (!enabled("auto_respawn")
                || client.player == null
                || !client.player.isDeadOrDying()
                || !(client.gui.screen() instanceof DeathScreen)) {
            autoRespawnTicks = 0;
            return;
        }
        autoRespawnTicks++;
        if (autoRespawnTicks >= 20) {
            client.player.respawn();
            client.gui.setScreen(null);
            autoRespawnTicks = 0;
        }
    }

    private void applyAntiAfk(Minecraft client, boolean canDriveKeys) {
        if (!enabled("anti_afk") || !canDriveKeys) {
            antiAfkTicks = 0;
            if (antiAfkApplied) {
                client.options.keyJump.setDown(false);
                antiAfkApplied = false;
            }
            return;
        }
        antiAfkTicks++;
        if (antiAfkApplied) {
            client.options.keyJump.setDown(false);
            antiAfkApplied = false;
        } else if (antiAfkTicks >= 1200 && !client.options.keyJump.isDown()) {
            client.options.keyJump.setDown(true);
            antiAfkApplied = true;
            antiAfkTicks = 0;
        }
    }

    private void applyClearWeather(Minecraft client) {
        boolean requested = enabled("clear_weather") && client.level != null;
        if (requested) {
            if (!clearWeatherApplied || weatherLevel != client.level) {
                restoreWeather();
                weatherLevel = client.level;
                previousRain = weatherLevel.getRainLevel(1.0F);
                previousThunder = weatherLevel.getThunderLevel(1.0F);
                clearWeatherApplied = true;
            }
            weatherLevel.setRainLevel(0.0F);
            weatherLevel.setThunderLevel(0.0F);
        } else {
            restoreWeather();
        }
    }

    private void restoreWeather() {
        if (clearWeatherApplied && weatherLevel != null) {
            weatherLevel.setRainLevel(previousRain);
            weatherLevel.setThunderLevel(previousThunder);
        }
        clearWeatherApplied = false;
        weatherLevel = null;
    }

    private void trackDeathPosition(Minecraft client) {
        if (!enabled("death_position") || client.player == null) {
            deathObserved = false;
            return;
        }
        boolean dead = client.player.isDeadOrDying();
        if (dead && !deathObserved) {
            deathObserved = true;
            String name = "death_" + LocalDateTime.now().format(DEATH_ID);
            String dimension = client.player.level().dimension().identifier().toString();
            Waypoint waypoint = new Waypoint(
                    name,
                    serverKey(client),
                    dimension,
                    client.player.getX(),
                    client.player.getY(),
                    client.player.getZ(),
                    0xFFFF5555,
                    true
            );
            waypoints.put(waypoint);
            lastDeathLabel = String.format(
                    Locale.ROOT,
                    "Death %.1f / %.1f / %.1f (%s)",
                    waypoint.x(),
                    waypoint.y(),
                    waypoint.z(),
                    dimension
            );
            save();
        } else if (!dead) {
            deathObserved = false;
        }
    }

    public int panic(Minecraft client) {
        int disabled = 0;
        for (RegisteredModule module : modules.all()) {
            if (module.enabled()
                    && module.descriptor().risk()
                    != dev.b2tclient.common.module.ModuleRisk.PASSIVE) {
                try {
                    module.setEnabled(false);
                    disabled++;
                } catch (RuntimeException exception) {
                    B2TClient26.LOGGER.error(
                            "Could not disable 26.2 module {} during panic",
                            module.descriptor().id(),
                            exception
                    );
                }
            }
        }
        releasePlatformState(client);
        save();
        return disabled;
    }

    private void releasePlatformState(Minecraft client) {
        if (autoWalkApplied) {
            client.options.keyUp.setDown(false);
            autoWalkApplied = false;
        }
        if (autoSprintApplied) {
            client.options.keySprint.setDown(false);
            autoSprintApplied = false;
        }
        if (antiAfkApplied) {
            client.options.keyJump.setDown(false);
            antiAfkApplied = false;
        }
        antiAfkTicks = 0;
        autoRespawnTicks = 0;
        deathObserved = false;
        // A death marker belongs to the server it happened on; carrying it into
        // the next connection would point at coordinates in another world.
        lastDeathLabel = "";
        keybinds.reset();
        utilityAutomation.release(client);
        autoMend.release(client);
        fastUse.release();
        autoCraft.release();
        utilityArbiter.releaseAll();
        if (baritone != null) {
            baritone.resetSession();
        }
        PlatformModuleSettings26.clearBaritoneSessionControls(modules);
        clearBaritoneSessionIdentity();
        pendingBaritoneTarget = null;
        pendingBaritoneStop = false;
        profileConnectionIdentity = null;
        profileServerKey = "";
        combatTarget.clear();
        releaseCombat(client);
        releaseMovement(client);
        movementNetwork.resetConnection();
        HudMetricsBridge26.reset();
        freecam.release(client);
        xray.release(client);
        chams.release();
        visualOverlays.reset();
        worldTrackers.release();
        worldTrackerRenderer.reset();
        restoreWeather();
        if (fullBrightApplied) {
            client.options.gamma().set(previousGamma);
            fullBrightApplied = false;
        }
        if (noViewBobApplied) {
            client.options.bobView().set(previousViewBob);
            noViewBobApplied = false;
        }
    }

    private void releaseCombat(Minecraft client) {
        combatInventory.release(client);
        combatAttack.release();
        combatCrystalMine.release(client);
        combatConstruction.release(client);
        combatBedAnchor.release(client);
        combatBowAim.release();
        combatQuiver.release(client);
        combatSiege.release(client);
        autoArmor.release(client);
        replenish.release();
        chestSwap.release(client);
        inventoryManager.release(client);
        combatArbiter.releaseAll();
    }

    private void releaseMovement(Minecraft client) {
        walkMovement.release(client);
        fallWaterMovement.release(movementArbiter);
        elytraSwap.release(client);
        elytraControl.release(client);
        movementInput.release(movementArbiter);
        movementArbiter.releaseAll();
        movementSafety.reset();
        movementSessionIdentity = null;
        activeMovementConnection = null;
    }

    private boolean enabled(String id) {
        return modules.find(id).map(RegisteredModule::enabled).orElse(false);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(B2TClient26.MOD_ID, path);
    }

    public HudLayout26 hudLayout() {
        return hudLayout;
    }

    public PresetApplication26 presets() {
        return presets;
    }

    public CombatTargetBridge26 combatTarget() {
        return combatTarget;
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public FriendBook friends() {
        return friends;
    }

    public WaypointBook waypoints() {
        return waypoints;
    }

    public ProfileBook profiles() {
        return profiles;
    }

    public BaritoneNavigator26 baritone() {
        return baritone;
    }

    public String requestBaritoneNavigation(int x, int y, int z) {
        if (Math.abs((long) x) > 30_000_000L
                || Math.abs((long) z) > 30_000_000L
                || y < -64
                || y > 319) {
            return "Baritone target is outside the supported world bounds";
        }
        Minecraft client = Minecraft.getInstance();
        if (baritone == null || !baritone.available()) {
            return "Compatible Baritone is not installed";
        }
        if (client.player == null
                || !client.player.isAlive()
                || client.player.isDeadOrDying()
                || client.level == null
                || client.getConnection() == null
                || !client.getConnection().getConnection().isConnected()) {
            return "Baritone navigation requires an active living play session";
        }
        if (enabled("freecam")
                || !combatArbiter.snapshot().channelGrants().isEmpty()) {
            return "Baritone navigation is blocked by another action owner";
        }
        pendingBaritoneStop = false;
        pendingBaritoneTarget =
                new BaritoneNavigator26.NavigationTarget(x, y, z);
        PlatformModuleSettings26.clearBaritoneConfirmation(modules);
        save();
        return "Baritone target queued: " + pendingBaritoneTarget;
    }

    public String requestBaritoneStop() {
        pendingBaritoneTarget = null;
        pendingBaritoneStop = true;
        PlatformModuleSettings26.clearBaritoneConfirmation(modules);
        save();
        return "Baritone stop queued";
    }

    public void clearBaritoneConfirmation() {
        pendingBaritoneTarget = null;
        PlatformModuleSettings26.clearBaritoneConfirmation(modules);
    }

    public String lastDeathLabel() {
        return lastDeathLabel;
    }

    public String serverKey(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server == null ? "singleplayer" : server.ip;
    }

    public void requestSave() {
        configSaves.markDirty();
    }

    public boolean save() {
        configSaves.markDirty();
        TickDebouncedSaveController.TickResult result = configSaves.flush();
        if (result == TickDebouncedSaveController.TickResult.FAILED) {
            configSaves.lastFailure().ifPresent(exception ->
                    B2TClient26.LOGGER.error(
                            "Could not atomically save the 26.2 config",
                            exception
                    )
            );
            return false;
        }
        return true;
    }

    private void saveImmediately() throws Exception {
        configStore.save(modules, profiles, friends, waypoints, hudLayout);
    }

    public String lastSaveFailure() {
        return configSaves.lastFailure()
                .map(Throwable::getMessage)
                .orElse("");
    }

    public boolean flushPendingSave() {
        try {
            TickDebouncedSaveController.TickResult result =
                    configSaves.flush();
            return result != TickDebouncedSaveController.TickResult.FAILED;
        } catch (RuntimeException exception) {
            B2TClient26.LOGGER.error(
                    "Could not flush the 26.2 config",
                    exception
            );
            return false;
        }
    }

    /**
     * Netty-thread bridge used by the read-only inbound packet mixin.
     */
    public void observeInbound(Connection connection, Packet<?> packet) {
        if (connection != null && connection == activeMovementConnection) {
            movementNetwork.observeInbound(packet);
        }
    }

    private record MovementSessionIdentity(
            Object connection,
            Object level,
            Object player
    ) {
    }
}
