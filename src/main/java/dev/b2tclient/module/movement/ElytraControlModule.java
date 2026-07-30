package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class ElytraControlModule extends Module implements TickableModule {
    private static final int PRIORITY = 60;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting cruiseSpeed = addSetting(new DoubleSetting(
            "cruise_speed",
            "Cruise speed",
            "Maximum controlled horizontal gliding speed.",
            1.25,
            0.40,
            2.00,
            0.05
    ));
    private final DoubleSetting acceleration = addSetting(new DoubleSetting(
            "acceleration",
            "Acceleration",
            "Horizontal speed change per tick.",
            0.04,
            0.01,
            0.12,
            0.01
    ));
    private final DoubleSetting verticalSpeed = addSetting(new DoubleSetting(
            "vertical_speed",
            "Vertical speed",
            "Rise or descent speed while holding jump or sneak.",
            0.25,
            0.05,
            0.50,
            0.05
    ));

    public ElytraControlModule(ActionCoordinator actions) {
        super(
                "elytra_control",
                "Elytra Control",
                "Adds bounded directional and vertical control during normal elytra flight.",
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
                || !minecraft.player.isFallFlying()
                || !decision.canApply()
                || !actions.claim(ActionCoordinator.Channel.MOVEMENT, id(), PRIORITY, 1)) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        Vec3 current = minecraft.player.getDeltaMovement();
        Vec3 direction = B2TMovementSupport.inputDirection(minecraft.player);
        boolean verticalInput = minecraft.options.keyJump.isDown()
                || minecraft.options.keyShift.isDown();
        if (direction == Vec3.ZERO && !verticalInput) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        double targetSpeed = cruiseSpeed.get() * decision.scale();
        double appliedAcceleration = acceleration.get() * decision.scale();
        double nextX = current.x;
        double nextZ = current.z;
        if (direction != Vec3.ZERO) {
            double targetX = direction.x * targetSpeed;
            double targetZ = direction.z * targetSpeed;
            nextX = approach(current.x, targetX, appliedAcceleration);
            nextZ = approach(current.z, targetZ, appliedAcceleration);
        }

        double nextY = current.y;
        if (minecraft.options.keyJump.isDown()) {
            nextY = approach(
                    current.y,
                    verticalSpeed.get() * decision.scale(),
                    appliedAcceleration
            );
        } else if (minecraft.options.keyShift.isDown()) {
            nextY = approach(
                    current.y,
                    -verticalSpeed.get() * decision.scale(),
                    appliedAcceleration
            );
        }

        double gradualLimit = Math.max(
                targetSpeed,
                current.horizontalDistance() - appliedAcceleration
        );
        Vec3 applied = B2TMovementSupport.horizontalAtMost(
                new Vec3(nextX, nextY, nextZ),
                gradualLimit
        );
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
