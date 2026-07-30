package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.TickableModule;
import net.minecraft.client.Minecraft;

public final class FullBrightModule extends Module implements TickableModule {
    private Double previousGamma;

    public FullBrightModule() {
        super(
                "full_bright",
                "Full Bright",
                "Keeps the vanilla brightness slider at its maximum while enabled.",
                Category.VISUAL,
                false
        );
    }

    @Override
    protected void onEnable(Minecraft minecraft) {
        previousGamma = minecraft.options.gamma().get();
        minecraft.options.gamma().set(1.0);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.options.gamma().get() < 1.0) {
            minecraft.options.gamma().set(1.0);
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        if (previousGamma != null) {
            minecraft.options.gamma().set(previousGamma);
            previousGamma = null;
        }
    }
}
