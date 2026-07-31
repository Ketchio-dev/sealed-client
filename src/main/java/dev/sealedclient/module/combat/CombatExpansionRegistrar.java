package dev.sealedclient.module.combat;

import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.RotationApplier;

import java.util.Objects;

public final class CombatExpansionRegistrar {
    private CombatExpansionRegistrar() {
    }

    public static void register(
            ModuleManager modules,
            FriendManager friends,
            ActionCoordinator actions,
            RotationApplier rotations
    ) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(friends, "friends");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(rotations, "rotations");

        modules.register(new OffhandModule(actions));
        modules.register(new AntiWeaknessModule(actions));
        modules.register(new CriticalsModule(friends, actions));
        modules.register(new KillAuraModule(friends, actions, rotations));
        modules.register(new SurroundModule(actions));
        modules.register(new HoleFillModule(friends, actions));
        modules.register(new AutoMineModule(friends, actions));
        modules.register(new AutoCrystalModule(friends, actions, rotations));
        modules.register(new SelfTrapModule(actions));
        modules.register(new AutoTrapModule(friends, actions));
        modules.register(new BurrowModule(actions));
        modules.register(new AnchorAuraModule(friends, actions, rotations));
        modules.register(new BedAuraModule(friends, actions, rotations));
        modules.register(new BowAimModule(friends, actions, rotations));
        modules.register(new QuiverModule(actions, rotations));
        modules.register(new CityBreakerModule(friends, actions));
        modules.register(new PistonCrystalModule(friends, actions, rotations));
    }
}
