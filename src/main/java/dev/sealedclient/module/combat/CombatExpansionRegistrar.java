package dev.sealedclient.module.combat;

import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;

import java.util.Objects;

public final class CombatExpansionRegistrar {
    private CombatExpansionRegistrar() {
    }

    public static void register(
            ModuleManager modules,
            FriendManager friends,
            ActionCoordinator actions
    ) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(friends, "friends");
        Objects.requireNonNull(actions, "actions");

        modules.register(new OffhandModule(actions));
        modules.register(new AntiWeaknessModule(actions));
        modules.register(new CriticalsModule(friends, actions));
        modules.register(new KillAuraModule(friends, actions));
        modules.register(new SurroundModule(actions));
        modules.register(new HoleFillModule(friends, actions));
        modules.register(new AutoMineModule(friends, actions));
        modules.register(new AutoCrystalModule(friends, actions));
        modules.register(new SelfTrapModule(actions));
        modules.register(new AutoTrapModule(friends, actions));
        modules.register(new BurrowModule(actions));
        modules.register(new AnchorAuraModule(friends, actions));
        modules.register(new BedAuraModule(friends, actions));
        modules.register(new BowAimModule(friends, actions));
        modules.register(new QuiverModule(actions));
        modules.register(new CityBreakerModule(friends, actions));
        modules.register(new PistonCrystalModule(friends, actions));
    }
}
