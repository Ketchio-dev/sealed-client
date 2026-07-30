package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.service.ActionCoordinator;
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
                safety.observe(B2TMovementSupport.safetyObservation(minecraft));
        if (!B2TMovementSupport.canControl(minecraft)
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
