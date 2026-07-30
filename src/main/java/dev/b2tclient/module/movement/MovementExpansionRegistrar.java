package dev.b2tclient.module.movement;

import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.service.ActionCoordinator;

import java.util.Objects;

/**
 * Registers the movement expansion without coupling the modules to the client
 * bootstrap. The caller owns the shared coordinator and its tick lifecycle.
 */
public final class MovementExpansionRegistrar {
    private MovementExpansionRegistrar() {
    }

    public static void register(ModuleManager modules, ActionCoordinator actions) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(actions, "actions");

        modules.register(new SafeWalkModule(actions));
        modules.register(new AutoCenterModule(actions));
        modules.register(new HoleSnapModule(actions));
        modules.register(new StepModule(actions));
        modules.register(new NoFallModule(actions));
        modules.register(new FastSwimModule(actions));
        modules.register(new JesusModule(actions));
        modules.register(new ElytraSwapModule(actions));
        modules.register(new ElytraControlModule(actions));
        modules.register(new GroundSpeedModule(actions));
        modules.register(new NoSlowModule());
        modules.register(new NoRotateModule());
    }
}
