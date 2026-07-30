package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class AutoCenterModule extends Module implements TickableModule {
    private static final int PRIORITY = 55;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting speed = addSetting(new DoubleSetting(
            "speed",
            "Speed",
            "Maximum centering speed per tick.",
            0.12,
            0.03,
            0.25,
            0.01
    ));
    private final DoubleSetting tolerance = addSetting(new DoubleSetting(
            "tolerance",
            "Tolerance",
            "Distance from the block center considered centered.",
            0.04,
            0.01,
            0.15,
            0.01
    ));

    public AutoCenterModule(ActionCoordinator actions) {
        super(
                "auto_center",
                "Auto Center",
                "Gently centers the player on the current block while idle.",
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
                || !decision.canApply()
                || B2TMovementSupport.inputDirection(minecraft.player) != Vec3.ZERO) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        BlockPos feet = BlockPos.containing(
                minecraft.player.getX(),
                minecraft.player.getY() + 0.05,
                minecraft.player.getZ()
        );
        if (minecraft.level.getBlockState(feet.below()).getCollisionShape(
                minecraft.level,
                feet.below()
        ).isEmpty()) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        double deltaX = feet.getX() + 0.5 - minecraft.player.getX();
        double deltaZ = feet.getZ() + 0.5 - minecraft.player.getZ();
        double distance = Math.hypot(deltaX, deltaZ);
        if (!actions.claim(ActionCoordinator.Channel.MOVEMENT, id(), PRIORITY, 1)) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        Vec3 velocity = minecraft.player.getDeltaMovement();
        if (distance <= tolerance.get()) {
            minecraft.player.setDeltaMovement(0.0, velocity.y, 0.0);
            return;
        }

        double appliedSpeed = Math.min(speed.get() * decision.scale(), distance);
        Vec3 applied = new Vec3(
                deltaX / distance * appliedSpeed,
                velocity.y,
                deltaZ / distance * appliedSpeed
        );
        minecraft.player.setDeltaMovement(applied);
        safety.recordApplied(
                applied.x,
                applied.y,
                applied.z
        );
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }
}
