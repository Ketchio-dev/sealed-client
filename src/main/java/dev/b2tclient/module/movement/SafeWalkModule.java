package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SafeWalkModule extends Module implements TickableModule {
    private static final int PRIORITY = 80;

    private final ActionCoordinator actions;
    private final DoubleSetting lookAhead = addSetting(new DoubleSetting(
            "look_ahead",
            "Look ahead",
            "Distance ahead used to detect an unsupported edge.",
            0.45,
            0.20,
            0.80,
            0.05
    ));

    public SafeWalkModule(ActionCoordinator actions) {
        super(
                "safe_walk",
                "Safe Walk",
                "Stops horizontal momentum before walking over an unsupported edge.",
                Category.MOVEMENT,
                false,
                ModuleRisk.MOVEMENT
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!B2TMovementSupport.canControl(minecraft)
                || !minecraft.player.onGround()
                || minecraft.player.isCrouching()
                || minecraft.player.isInLiquid()
                || minecraft.player.isFallFlying()) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        Vec3 direction = B2TMovementSupport.inputDirection(minecraft.player);
        if (direction == Vec3.ZERO) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        double distance = lookAhead.get();
        AABB supportProbe = minecraft.player.getBoundingBox()
                .move(direction.x * distance, -0.16, direction.z * distance);
        if (!minecraft.level.noCollision(minecraft.player, supportProbe)) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        if (actions.claim(ActionCoordinator.Channel.MOVEMENT, id(), PRIORITY, 1)) {
            Vec3 velocity = minecraft.player.getDeltaMovement();
            minecraft.player.setDeltaMovement(0.0, velocity.y, 0.0);
            minecraft.player.input.forwardImpulse = 0.0f;
            minecraft.player.input.leftImpulse = 0.0f;
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        actions.releaseOwner(minecraft, id());
    }
}
