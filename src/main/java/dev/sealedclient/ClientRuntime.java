package dev.sealedclient;

import dev.sealedclient.config.ConfigManager;
import dev.sealedclient.command.CommandManager;
import dev.sealedclient.api.SealedAddon;
import dev.sealedclient.common.module.BuiltinModuleCatalog;
import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.event.ClientTickEvent;
import dev.sealedclient.event.EventBus;
import dev.sealedclient.event.PacketEvent;
import dev.sealedclient.gui.ClickGuiScreen;
import dev.sealedclient.hud.HudEditorScreen;
import dev.sealedclient.hud.HudRenderer;
import dev.sealedclient.hud.NotificationManager;
import dev.sealedclient.input.ModuleKeybindController;
import dev.sealedclient.integration.OptionalIntegrationManager;
import dev.sealedclient.module.combat.AutoTotemModule;
import dev.sealedclient.module.combat.AutoWeaponModule;
import dev.sealedclient.module.combat.CombatExpansionRegistrar;
import dev.sealedclient.module.combat.TriggerBotModule;
import dev.sealedclient.module.hud.ArmorModule;
import dev.sealedclient.module.hud.BiomeModule;
import dev.sealedclient.module.hud.ClockModule;
import dev.sealedclient.module.hud.CoordinatesModule;
import dev.sealedclient.module.hud.DeathPositionModule;
import dev.sealedclient.module.hud.DirectionModule;
import dev.sealedclient.module.hud.DurabilityWarningModule;
import dev.sealedclient.module.hud.EffectsModule;
import dev.sealedclient.module.hud.FpsModule;
import dev.sealedclient.module.hud.HealthModule;
import dev.sealedclient.module.hud.InventorySpaceModule;
import dev.sealedclient.module.hud.PingModule;
import dev.sealedclient.module.hud.PlayerCountModule;
import dev.sealedclient.module.hud.RadarModule;
import dev.sealedclient.module.hud.SessionModule;
import dev.sealedclient.module.hud.SpeedModule;
import dev.sealedclient.module.hud.SuppliesModule;
import dev.sealedclient.module.hud.TotemCountModule;
import dev.sealedclient.module.hud.WatermarkModule;
import dev.sealedclient.module.movement.AutoSprintModule;
import dev.sealedclient.module.movement.AutoWalkModule;
import dev.sealedclient.module.movement.MovementExpansionRegistrar;
import dev.sealedclient.module.movement.MovementNetworkTracker;
import dev.sealedclient.module.utility.AutoArmorModule;
import dev.sealedclient.module.utility.AutoEatModule;
import dev.sealedclient.module.utility.AutoDisconnectModule;
import dev.sealedclient.module.utility.AutoToolModule;
import dev.sealedclient.module.utility.BaritoneNavigatorModule;
import dev.sealedclient.module.utility.UtilityHudExpansionRegistrar;
import dev.sealedclient.module.visual.ClearWeatherModule;
import dev.sealedclient.module.visual.FullBrightModule;
import dev.sealedclient.module.visual.NoViewBobModule;
import dev.sealedclient.module.visual.VisualWorldExpansionRegistrar;
import dev.sealedclient.render.WorldOverlayRenderer;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.RotationApplier;
import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.WaypointManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public final class ClientRuntime {
    private final ModuleManager moduleManager = new ModuleManager();
    private final FriendManager friendManager = new FriendManager();
    private final WaypointManager waypointManager = new WaypointManager();
    private final EventBus eventBus = new EventBus();
    private final ActionCoordinator actionCoordinator = new ActionCoordinator();
    private final RotationApplier rotationApplier = new RotationApplier();
    private final MovementNetworkTracker movementNetworkTracker =
            new MovementNetworkTracker();
    private final OptionalIntegrationManager integrationManager =
            new OptionalIntegrationManager();
    private final ConfigManager configManager = new ConfigManager(
            moduleManager,
            friendManager,
            waypointManager
    );
    private final CommandManager commandManager = new CommandManager(
            moduleManager,
            configManager,
            friendManager,
            waypointManager,
            actionCoordinator,
            integrationManager.baritone(),
            movementNetworkTracker::snapshot
    );
    private final NotificationManager notificationManager = new NotificationManager();
    private final HudRenderer hudRenderer = new HudRenderer(moduleManager, notificationManager);
    private final ModuleKeybindController keybindController =
            new ModuleKeybindController(moduleManager, configManager, notificationManager);

    private KeyMapping openGuiKey;
    private KeyMapping openHudEditorKey;
    private WorldOverlayRenderer worldOverlayRenderer;

    public void initialize() {
        registerModules();
        initializeAddons();
        eventBus.subscribe(
                PacketEvent.class,
                movementNetworkTracker::observe
        );

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sealedclient.open_gui",
                GLFW.GLFW_KEY_P,
                "category.sealedclient"
        ));
        openHudEditorKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sealedclient.open_hud_editor",
                GLFW.GLFW_KEY_H,
                "category.sealedclient"
        ));

        hudRenderer.initialize();
        worldOverlayRenderer.initialize();
        commandManager.initialize();

        ClientLifecycleEvents.CLIENT_STARTED.register(configManager::load);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            integrationManager.baritone().resetSession();
            configManager.save();
            moduleManager.shutdown(client);
            actionCoordinator.releaseAll(client);
            rotationApplier.reset();
            notificationManager.clear();
            eventBus.clear();
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            actionCoordinator.beginTick(client);
            rotationApplier.beginTick();
            eventBus.post(new ClientTickEvent(
                    client,
                    ClientTickEvent.Phase.PRE,
                    actionCoordinator.tick()
            ));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.consumeClick()) {
                if (client.screen instanceof ClickGuiScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new ClickGuiScreen(moduleManager, configManager));
                }
            }
            while (openHudEditorKey.consumeClick()) {
                if (client.screen instanceof HudEditorScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new HudEditorScreen(moduleManager, configManager));
                }
            }

            keybindController.tick(client);
            if (moduleManager.tick(client)) {
                configManager.save();
            }
            rotationApplier.endTick(client);
            eventBus.post(new ClientTickEvent(
                    client,
                    ClientTickEvent.Phase.POST,
                    actionCoordinator.tick()
            ));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            integrationManager.baritone().resetSession();
            String server = client.getCurrentServer() == null
                    ? "singleplayer"
                    : client.getCurrentServer().ip;
            configManager.profileForServer(server).ifPresent(profile ->
                    configManager.switchProfile(profile, client)
            );
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            integrationManager.baritone().resetSession();
            actionCoordinator.releaseAll(client);
            rotationApplier.reset();
            movementNetworkTracker.reset();
        });

        SealedClient.LOGGER.info(
                "{} initialized with {} modules",
                SealedClient.DISPLAY_NAME,
                moduleManager.all().size()
        );
    }

    public ModuleManager modules() {
        return moduleManager;
    }

    public ConfigManager config() {
        return configManager;
    }

    public EventBus events() {
        return eventBus;
    }

    public ActionCoordinator actions() {
        return actionCoordinator;
    }

    public FriendManager friends() {
        return friendManager;
    }

    public WaypointManager waypoints() {
        return waypointManager;
    }

    public CommandManager commands() {
        return commandManager;
    }

    public OptionalIntegrationManager integrations() {
        return integrationManager;
    }

    public NotificationManager notifications() {
        return notificationManager;
    }

    public MovementNetworkTracker movementNetwork() {
        return movementNetworkTracker;
    }

    private void registerModules() {
        moduleManager.register(new WatermarkModule());
        moduleManager.register(new CoordinatesModule());
        moduleManager.register(new DirectionModule());
        moduleManager.register(new SpeedModule());
        moduleManager.register(new FpsModule());
        moduleManager.register(new PingModule());
        moduleManager.register(new HealthModule());
        moduleManager.register(new TotemCountModule());
        moduleManager.register(new ArmorModule());
        moduleManager.register(new DurabilityWarningModule());
        moduleManager.register(new BiomeModule());
        moduleManager.register(new PlayerCountModule());
        moduleManager.register(new InventorySpaceModule());
        moduleManager.register(new SuppliesModule());
        moduleManager.register(new EffectsModule());
        moduleManager.register(new RadarModule());
        moduleManager.register(new SessionModule());
        moduleManager.register(new ClockModule());
        moduleManager.register(new DeathPositionModule(configManager::save));

        moduleManager.register(new AutoTotemModule(actionCoordinator));
        moduleManager.register(new AutoWeaponModule(actionCoordinator));
        moduleManager.register(new TriggerBotModule(friendManager));
        CombatExpansionRegistrar.register(
                moduleManager,
                friendManager,
                actionCoordinator,
                rotationApplier
        );

        moduleManager.register(new ClearWeatherModule());
        moduleManager.register(new FullBrightModule());
        moduleManager.register(new NoViewBobModule());

        moduleManager.register(new AutoWalkModule(actionCoordinator));
        moduleManager.register(new AutoSprintModule());
        MovementExpansionRegistrar.register(moduleManager, actionCoordinator);

        moduleManager.register(new AutoEatModule(actionCoordinator));
        moduleManager.register(new AutoDisconnectModule());
        moduleManager.register(new AutoArmorModule(actionCoordinator));
        moduleManager.register(new AutoToolModule(actionCoordinator));
        UtilityHudExpansionRegistrar.register(
                moduleManager,
                configManager,
                friendManager,
                waypointManager,
                actionCoordinator,
                rotationApplier
        );
        moduleManager.register(new BaritoneNavigatorModule(integrationManager.baritone()));

        worldOverlayRenderer = VisualWorldExpansionRegistrar.register(
                moduleManager,
                friendManager,
                waypointManager,
                actionCoordinator
        );
        validateBuiltinCatalog();
    }

    private void validateBuiltinCatalog() {
        Map<String, dev.sealedclient.core.Module> runtimeModules = new HashMap<>();
        for (dev.sealedclient.core.Module module : moduleManager.all()) {
            runtimeModules.put(module.id(), module);
        }

        if (runtimeModules.size() != BuiltinModuleCatalog.EXPECTED_MODULE_COUNT) {
            throw new IllegalStateException(
                    "Built-in module count does not match the shared catalog: "
                            + runtimeModules.size()
                            + " != "
                            + BuiltinModuleCatalog.EXPECTED_MODULE_COUNT
            );
        }

        for (BuiltinModuleCatalog.CatalogEntry entry : BuiltinModuleCatalog.entries()) {
            dev.sealedclient.core.Module module = runtimeModules.remove(entry.id());
            if (module == null) {
                throw new IllegalStateException(
                        "Shared catalog module is missing from the 1.21.4 runtime: " + entry.id()
                );
            }
            if (!module.category().name().equals(entry.category().name())) {
                throw new IllegalStateException(
                        "Category mismatch for " + entry.id() + ": "
                                + module.category() + " != " + entry.category()
                );
            }
            if (!module.risk().name().equals(entry.risk().name())) {
                throw new IllegalStateException(
                        "Risk mismatch for " + entry.id() + ": "
                                + module.risk() + " != " + entry.risk()
                );
            }
        }

        if (!runtimeModules.isEmpty()) {
            throw new IllegalStateException(
                    "1.21.4 runtime modules are missing from the shared catalog: "
                            + runtimeModules.keySet()
            );
        }
    }

    private void initializeAddons() {
        for (SealedAddon addon : FabricLoader.getInstance()
                .getEntrypoints("sealedclient:addon", SealedAddon.class)) {
            try {
                addon.onInitialize();
            } catch (RuntimeException exception) {
                SealedClient.LOGGER.error("Could not initialize a Sealed addon", exception);
            }
        }
    }
}
