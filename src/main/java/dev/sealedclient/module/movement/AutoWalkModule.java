package dev.sealedclient.module.movement;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;

public final class AutoWalkModule extends Module implements TickableModule {
    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();

    public AutoWalkModule() {
        this(new ActionCoordinator());
    }

    public AutoWalkModule(ActionCoordinator actions) {
        super(
                "auto_walk",
                "Auto Walk",
                "Holds the forward key until disabled.",
                Category.MOVEMENT,
                false,
                ModuleRisk.MOVEMENT
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        MovementSafetyController.Decision decision =
                safety.observe(SealedMovementSupport.safetyObservation(minecraft));
        if (!SealedMovementSupport.canControl(minecraft)
                || decision.state() != MovementSafetyController.State.ACTIVE) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        boolean controlled = actions.setKey(
                minecraft,
                ActionCoordinator.Channel.MOVEMENT,
                id(),
                10,
                minecraft.options.keyUp,
                true
        );
        if (!controlled) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        var movement = minecraft.player.getDeltaMovement();
        safety.recordApplied(movement.x, movement.y, movement.z);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }
}
