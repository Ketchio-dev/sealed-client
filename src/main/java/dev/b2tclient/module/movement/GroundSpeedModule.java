package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class GroundSpeedModule extends Module implements TickableModule {
    private static final int PRIORITY = 35;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting speed = addSetting(new DoubleSetting(
            "speed",
            "Speed",
            "Bounded target ground speed.",
            0.31,
            0.20,
            0.45,
            0.01
    ));
    private final DoubleSetting acceleration = addSetting(new DoubleSetting(
            "acceleration",
            "Acceleration",
            "Horizontal speed change per tick.",
            0.06,
            0.01,
            0.12,
            0.01
    ));

    public GroundSpeedModule(ActionCoordinator actions) {
        super(
                "ground_speed",
                "Speed",
                "Adds modest, bounded acceleration while moving on solid ground.",
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
                || !minecraft.player.onGround()
                || minecraft.player.isInLiquid()
                || minecraft.player.isFallFlying()
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
        double appliedAcceleration = acceleration.get() * decision.scale();
        double targetX = direction.x * targetSpeed;
        double targetZ = direction.z * targetSpeed;
        Vec3 next = new Vec3(
                approach(current.x, targetX, appliedAcceleration),
                current.y,
                approach(current.z, targetZ, appliedAcceleration)
        );
        double gradualLimit = Math.max(
                targetSpeed,
                current.horizontalDistance() - appliedAcceleration
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

    private static double approach(double current, double target, double amount) {
        if (current < target) {
            return Math.min(current + amount, target);
        }
        return Math.max(current - amount, target);
    }
}
