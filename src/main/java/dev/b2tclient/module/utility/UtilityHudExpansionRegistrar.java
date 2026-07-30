package dev.b2tclient.module.utility;

import dev.b2tclient.config.ConfigManager;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.module.hud.ArrayListHudModule;
import dev.b2tclient.module.hud.ServerInfoHudModule;
import dev.b2tclient.module.hud.TargetHudModule;
import dev.b2tclient.module.hud.TickRateHudModule;
import dev.b2tclient.module.hud.TotemPopHudModule;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.WaypointManager;

import java.util.Objects;

/**
 * Single integration point for the reliability, inventory, and HUD expansion.
 * Keeping registration here lets the runtime add the complete feature group
 * without duplicating constructor wiring.
 */
public final class UtilityHudExpansionRegistrar {
    private UtilityHudExpansionRegistrar() {
    }

    public static void register(
            ModuleManager modules,
            ConfigManager config,
            FriendManager friends,
            WaypointManager waypoints,
            ActionCoordinator actions
    ) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(friends, "friends");
        Objects.requireNonNull(waypoints, "waypoints");
        Objects.requireNonNull(actions, "actions");

        modules.register(new ArrayListHudModule(modules));
        modules.register(new TickRateHudModule());
        modules.register(new TargetHudModule(friends));
        modules.register(new ServerInfoHudModule());
        modules.register(new TotemPopHudModule());

        modules.register(new ReplenishModule(actions));
        modules.register(new AutoRespawnModule());
        modules.register(new AutoReconnectModule(actions));
        modules.register(new AntiAfkModule(actions));
        modules.register(new ChestSwapModule(actions));
        modules.register(new AutoMendModule(actions));
        modules.register(new FastUseModule(actions));
        modules.register(new InventoryManagerModule(actions));
        modules.register(new AutoCraftModule(actions));
    }
}
