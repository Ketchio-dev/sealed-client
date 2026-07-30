package dev.sealedclient.module.utility;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.IntegerSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

public final class AutoRespawnModule extends Module implements TickableModule {
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks to wait on the death screen before respawning.",
            10,
            0,
            100,
            1
    ));
    private int remaining = -1;

    public AutoRespawnModule() {
        super(
                "auto_respawn",
                "Auto Respawn",
                "Respawns after the vanilla death screen appears.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!(minecraft.screen instanceof DeathScreen)
                || minecraft.player == null
                || !minecraft.player.isDeadOrDying()) {
            remaining = -1;
            return;
        }

        if (remaining < 0) {
            remaining = delay.get();
        }
        if (remaining-- > 0) {
            return;
        }

        minecraft.player.respawn();
        minecraft.setScreen(null);
        remaining = -1;
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        remaining = -1;
    }
}
