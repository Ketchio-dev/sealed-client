package dev.b2tclient;

import dev.b2tclient.config.ConfigManager;
import dev.b2tclient.command.CommandManager;
import dev.b2tclient.api.B2TAddon;
import dev.b2tclient.common.module.BuiltinModuleCatalog;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.event.ClientTickEvent;
import dev.b2tclient.event.EventBus;
import dev.b2tclient.event.PacketEvent;
import dev.b2tclient.gui.ClickGuiScreen;
import dev.b2tclient.hud.HudEditorScreen;
import dev.b2tclient.hud.HudRenderer;
import dev.b2tclient.hud.NotificationManager;
import dev.b2tclient.input.ModuleKeybindController;
import dev.b2tclient.integration.OptionalIntegrationManager;
import dev.b2tclient.module.combat.AutoTotemModule;
import dev.b2tclient.module.combat.AutoWeaponModule;
import dev.b2tclient.module.combat.CombatExpansionRegistrar;
import dev.b2tclient.module.combat.TriggerBotModule;
import dev.b2tclient.module.hud.ArmorModule;
import dev.b2tclient.module.hud.BiomeModule;
import dev.b2tclient.module.hud.ClockModule;
import dev.b2tclient.module.hud.CoordinatesModule;
import dev.b2tclient.module.hud.DeathPositionModule;
import dev.b2tclient.module.hud.DirectionModule;
import dev.b2tclient.module.hud.DurabilityWarningModule;
import dev.b2tclient.module.hud.EffectsModule;
import dev.b2tclient.module.hud.FpsModule;
import dev.b2tclient.module.hud.HealthModule;
import dev.b2tclient.module.hud.InventorySpaceModule;
import dev.b2tclient.module.hud.PingModule;
import dev.b2tclient.module.hud.PlayerCountModule;
import dev.b2tclient.module.hud.RadarModule;
import dev.b2tclient.module.hud.SessionModule;
import dev.b2tclient.module.hud.SpeedModule;
import dev.b2tclient.module.hud.SuppliesModule;
import dev.b2tclient.module.hud.TotemCountModule;
import dev.b2tclient.module.hud.WatermarkModule;
import dev.b2tclient.module.movement.AutoSprintModule;
import dev.b2tclient.module.movement.AutoWalkModule;
import dev.b2tclient.module.movement.MovementExpansionRegistrar;
import dev.b2tclient.module.movement.MovementNetworkTracker;
import dev.b2tclient.module.utility.AutoArmorModule;
import dev.b2tclient.module.utility.AutoEatModule;
import dev.b2tclient.module.utility.AutoDisconnectModule;
import dev.b2tclient.module.utility.AutoToolModule;
import dev.b2tclient.module.utility.BaritoneNavigatorModule;
import dev.b2tclient.module.utility.UtilityHudExpansionRegistrar;
import dev.b2tclient.module.visual.ClearWeatherModule;
import dev.b2tclient.module.visual.FullBrightModule;
import dev.b2tclient.module.visual.NoViewBobModule;
import dev.b2tclient.module.visual.VisualWorldExpansionRegistrar;
import dev.b2tclient.render.WorldOverlayRenderer;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.WaypointManager;
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
                "key.b2tclient.open_gui",
                GLFW.GLFW_KEY_P,
                "category.b2tclient"
        ));
        openHudEditorKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.b2tclient.open_hud_editor",
                GLFW.GLFW_KEY_H,
                "category.b2tclient"
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
            notificationManager.clear();
            eventBus.clear();
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            actionCoordinator.beginTick(client);
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
            movementNetworkTracker.reset();
        });

        B2TClient.LOGGER.info(
                "{} initialized with {} modules",
                B2TClient.DISPLAY_NAME,
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
                actionCoordinator
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
                actionCoordinator
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
        Map<String, dev.b2tclient.core.Module> runtimeModules = new HashMap<>();
        for (dev.b2tclient.core.Module module : moduleManager.all()) {
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
            dev.b2tclient.core.Module module = runtimeModules.remove(entry.id());
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
        for (B2TAddon addon : FabricLoader.getInstance()
                .getEntrypoints("b2tclient:addon", B2TAddon.class)) {
            try {
                addon.onInitialize();
            } catch (RuntimeException exception) {
                B2TClient.LOGGER.error("Could not initialize a B2T addon", exception);
            }
        }
    }
}
