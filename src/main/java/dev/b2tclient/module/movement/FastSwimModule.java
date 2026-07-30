package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class FastSwimModule extends Module implements TickableModule {
    private static final int PRIORITY = 40;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting speed = addSetting(new DoubleSetting(
            "speed",
            "Speed",
            "Target horizontal swimming speed.",
            0.22,
            0.12,
            0.36,
            0.01
    ));

    public FastSwimModule(ActionCoordinator actions) {
        super(
                "fast_swim",
                "Fast Swim",
                "Adds bounded horizontal acceleration while actively swimming.",
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
                || !minecraft.player.isInWater()
                || !decision.canApply()) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        Vec3 direction = B2TMovementSupport.inputDirection(minecraft.player);
        if (direction == Vec3.ZERO) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        if (!actions.claim(ActionCoordinator.Channel.MOVEMENT, id(), PRIORITY, 1)) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        Vec3 current = minecraft.player.getDeltaMovement();
        double targetSpeed = speed.get() * decision.scale();
        double blend = 0.24 * decision.scale();
        Vec3 next = new Vec3(
                current.x + (direction.x * targetSpeed - current.x) * blend,
                current.y,
                current.z + (direction.z * targetSpeed - current.z) * blend
        );
        double gradualLimit = Math.max(
                targetSpeed,
                current.horizontalDistance() - speed.get() * blend
        );
        Vec3 applied = B2TMovementSupport.horizontalAtMost(next, gradualLimit);
        minecraft.player.setDeltaMovement(applied);
        safety.recordApplied(applied.x, applied.y, applied.z);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }
}
