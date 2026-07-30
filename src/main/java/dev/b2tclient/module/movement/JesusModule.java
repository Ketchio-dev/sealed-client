package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class JesusModule extends Module implements TickableModule {
    private static final int PRIORITY = 65;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting buoyancy = addSetting(new DoubleSetting(
            "buoyancy",
            "Buoyancy",
            "Maximum upward surface-assist velocity.",
            0.08,
            0.02,
            0.12,
            0.01
    ));

    public JesusModule(ActionCoordinator actions) {
        super(
                "jesus",
                "Jesus",
                "Conservatively keeps the player at the water surface without collision spoofing.",
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
                || minecraft.player.isUnderWater()
                || minecraft.options.keyShift.isDown()
                || !decision.canApply()
                || !actions.claim(ActionCoordinator.Channel.MOVEMENT, id(), PRIORITY, 1)) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        Vec3 velocity = minecraft.player.getDeltaMovement();
        double targetBuoyancy = buoyancy.get() * decision.scale();
        if (velocity.y < targetBuoyancy) {
            Vec3 applied = new Vec3(velocity.x, targetBuoyancy, velocity.z);
            minecraft.player.setDeltaMovement(applied);
            safety.recordApplied(applied.x, applied.y, applied.z);
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }
}
