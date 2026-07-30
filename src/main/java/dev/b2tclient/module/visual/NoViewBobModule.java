package dev.b2tclient.module.visual;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.TickableModule;
import net.minecraft.client.Minecraft;

public final class NoViewBobModule extends Module implements TickableModule {
    private Boolean previousValue;

    public NoViewBobModule() {
        super(
                "no_view_bob",
                "No View Bob",
                "Disables camera bobbing and restores the previous option when disabled.",
                Category.VISUAL,
                false
        );
    }

    @Override
    protected void onEnable(Minecraft minecraft) {
        previousValue = minecraft.options.bobView().get();
        minecraft.options.bobView().set(false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.options.bobView().get()) {
            minecraft.options.bobView().set(false);
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        if (previousValue != null) {
            minecraft.options.bobView().set(previousValue);
            previousValue = null;
        }
    }
}
