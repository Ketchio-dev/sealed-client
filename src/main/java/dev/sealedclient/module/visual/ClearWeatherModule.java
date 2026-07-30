package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.TickableModule;
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
