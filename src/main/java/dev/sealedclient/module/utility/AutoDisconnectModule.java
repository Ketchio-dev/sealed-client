package dev.sealedclient.module.utility;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.DoubleSetting;
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
                    "Sealed Client: auto-disconnected at %.1f health",
                    currentHealth
            )));
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        triggered = false;
    }
}
