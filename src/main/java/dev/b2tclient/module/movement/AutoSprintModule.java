package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import net.minecraft.client.Minecraft;

public final class AutoSprintModule extends Module implements TickableModule {
    private final BooleanSetting requireForward = addSetting(new BooleanSetting(
            "require_forward",
            "Forward only",
            "Sprint only while moving forward.",
            true
    ));

    public AutoSprintModule() {
        super(
                "auto_sprint",
                "Auto Sprint",
                "Automatically starts sprinting when possible.",
                Category.MOVEMENT,
                false,
                ModuleRisk.MOVEMENT
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }

        boolean moving = requireForward.get()
                ? minecraft.player.input.forwardImpulse > 0.0f
                : Math.abs(minecraft.player.input.forwardImpulse) > 0.0f
                || Math.abs(minecraft.player.input.leftImpulse) > 0.0f;

        if (moving
                && !minecraft.player.isCrouching()
                && minecraft.player.getFoodData().getFoodLevel() > 6) {
            minecraft.player.setSprinting(true);
        }
    }
}
