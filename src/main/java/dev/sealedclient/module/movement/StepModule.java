package dev.sealedclient.module.movement;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class StepModule extends Module implements TickableModule {
    private static final double VANILLA_STEP_HEIGHT = 0.6;
    private static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("sealedclient", "step_height");
    private static final int PRIORITY = 45;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting height = addSetting(new DoubleSetting(
            "height",
            "Height",
            "Maximum full-block step height.",
            1.0,
            0.6,
            1.5,
            0.1
    ));
    private LocalPlayer modifiedPlayer;

    public StepModule(ActionCoordinator actions) {
        super(
                "step",
                "Step",
                "Uses the vanilla step-height attribute to climb full blocks smoothly.",
                Category.MOVEMENT,
                false,
                ModuleRisk.MOVEMENT
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (modifiedPlayer != null && modifiedPlayer != minecraft.player) {
            removeModifier(modifiedPlayer);
            modifiedPlayer = null;
        }
        MovementSafetyController.Decision decision =
                safety.observe(SealedMovementSupport.safetyObservation(minecraft));
        if (!SealedMovementSupport.canControl(minecraft)
                || minecraft.player.isFallFlying()
                || minecraft.player.isInLiquid()
                || !decision.canApply()
                || !actions.claim(ActionCoordinator.Channel.MOVEMENT, id(), PRIORITY, 1)) {
            removeModifier(minecraft.player);
            modifiedPlayer = null;
            actions.releaseOwner(minecraft, id());
            return;
        }

        AttributeInstance attribute = minecraft.player.getAttribute(Attributes.STEP_HEIGHT);
        if (attribute == null) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        attribute.removeModifier(MODIFIER_ID);
        double targetHeight = VANILLA_STEP_HEIGHT
                + (height.get() - VANILLA_STEP_HEIGHT) * decision.scale();
        double amount = Math.max(0.0, targetHeight - attribute.getValue());
        if (amount <= 1.0E-4) {
            modifiedPlayer = null;
            actions.releaseOwner(minecraft, id());
            return;
        }
        attribute.addOrUpdateTransientModifier(new AttributeModifier(
                MODIFIER_ID,
                amount,
                AttributeModifier.Operation.ADD_VALUE
        ));
        modifiedPlayer = minecraft.player;
        var movement = minecraft.player.getDeltaMovement();
        safety.recordApplied(movement.x, movement.y, movement.z);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        removeModifier(modifiedPlayer);
        if (minecraft.player != modifiedPlayer) {
            removeModifier(minecraft.player);
        }
        modifiedPlayer = null;
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }

    private static void removeModifier(LocalPlayer player) {
        if (player == null) {
            return;
        }
        AttributeInstance attribute = player.getAttribute(Attributes.STEP_HEIGHT);
        if (attribute != null) {
            attribute.removeModifier(MODIFIER_ID);
        }
    }
}
