package dev.b2tclient.module.visual;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.TickableModule;
import net.minecraft.client.Minecraft;

public final class ClearWeatherModule extends Module implements TickableModule {
    public ClearWeatherModule() {
        super(
                "clear_weather",
                "Clear Weather",
                "Hides rain and thunder on the client.",
                Category.VISUAL,
                false
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.level == null) {
            return;
        }
        minecraft.level.setRainLevel(0.0f);
        minecraft.level.setThunderLevel(0.0f);
    }
}
