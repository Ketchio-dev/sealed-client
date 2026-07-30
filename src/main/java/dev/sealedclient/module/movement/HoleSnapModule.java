package dev.sealedclient.module.movement;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class HoleSnapModule extends Module implements TickableModule {
    private static final int PRIORITY = 75;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final IntegerSetting radius = addSetting(new IntegerSetting(
            "radius",
            "Radius",
            "Horizontal search radius for blast-resistant one-block holes.",
            3,
            1,
            5,
            1
    ));
    private final DoubleSetting speed = addSetting(new DoubleSetting(
            "speed",
            "Speed",
            "Maximum horizontal snap speed.",
            0.20,
            0.05,
            0.35,
            0.01
    ));

    public HoleSnapModule(ActionCoordinator actions) {
        super(
                "hole_snap",
                "Hole Snap",
                "Moves toward the nearest bedrock or obsidian-strength one-block hole.",
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
                || minecraft.player.isInLiquid()
                || minecraft.player.isFallFlying()
                || !decision.canApply()
                || minecraft.options.keyJump.isDown()) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        BlockPos hole = SealedMovementSupport.findNearestSafeHole(
                minecraft.level,
                minecraft.player,
                radius.get()
        );
        if (hole == null) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        double deltaX = hole.getX() + 0.5 - minecraft.player.getX();
        double deltaZ = hole.getZ() + 0.5 - minecraft.player.getZ();
        double distance = Math.hypot(deltaX, deltaZ);
        if (distance > radius.get() + 0.75
                || Math.abs(hole.getY() - minecraft.player.getY()) > 1.25) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        if (!actions.claim(ActionCoordinator.Channel.MOVEMENT, id(), PRIORITY, 1)) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        Vec3 velocity = minecraft.player.getDeltaMovement();
        if (distance < 0.035) {
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
        safety.recordApplied(applied.x, applied.y, applied.z);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }
}
