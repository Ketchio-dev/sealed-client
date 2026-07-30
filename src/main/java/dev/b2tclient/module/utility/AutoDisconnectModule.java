package dev.b2tclient.module.utility;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class AutoDisconnectModule extends Module implements TickableModule {
    private final DoubleSetting health = addSetting(new DoubleSetting(
            "health",
            "Health",
            "Disconnect when health plus absorption reaches this value.",
            6.0,
            1.0,
            20.0,
            0.5
    ));

    private boolean triggered;

    public AutoDisconnectModule() {
        super(
                "auto_disconnect",
                "Auto Disconnect",
                "Locally disconnects at low health.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            triggered = false;
            return;
        }

        float currentHealth = minecraft.player.getHealth() + minecraft.player.getAbsorptionAmount();
        if (!triggered && currentHealth <= health.get()) {
            triggered = true;
            minecraft.getConnection().getConnection().disconnect(Component.literal(String.format(
                    Locale.ROOT,
                    "B2T Client: auto-disconnected at %.1f health",
                    currentHealth
            )));
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        triggered = false;
    }
}
