package dev.sealedclient.module.utility;

import dev.sealedclient.config.ConfigManager;
import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.module.hud.ArrayListHudModule;
import dev.sealedclient.module.hud.ServerInfoHudModule;
import dev.sealedclient.module.hud.TargetHudModule;
import dev.sealedclient.module.hud.TickRateHudModule;
import dev.sealedclient.module.hud.TotemPopHudModule;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.RotationApplier;
import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.WaypointManager;

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
            ActionCoordinator actions,
            RotationApplier rotations
    ) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(friends, "friends");
        Objects.requireNonNull(waypoints, "waypoints");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(rotations, "rotations");

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
        modules.register(new AutoMendModule(actions, rotations));
        modules.register(new FastUseModule(actions));
        modules.register(new InventoryManagerModule(actions));
        modules.register(new AutoCraftModule(actions));
    }
}
