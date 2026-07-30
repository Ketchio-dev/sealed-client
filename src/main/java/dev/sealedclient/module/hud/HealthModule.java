package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;

public final class HealthModule extends HudModule implements TickableModule {
    private String displayText;
    private int displayColor = HudRenderContext.TEXT;
    private float displayedHealth = Float.NaN;
    private float displayedAbsorption = Float.NaN;
    private int displayedArmor = -1;

    public HealthModule() {
        super("health", "Health", "Displays health, absorption, and armor points.", true);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            displayText = null;
            return;
        }

        float health = player.getHealth();
        float absorption = player.getAbsorptionAmount();
        int armor = player.getArmorValue();
        if (displayText != null
                && displayedHealth == health
                && displayedAbsorption == absorption
                && displayedArmor == armor) {
            return;
        }
        displayedHealth = health;
        displayedAbsorption = absorption;
        displayedArmor = armor;
        displayColor = health + absorption <= 8.0f
                ? HudRenderContext.WARNING
                : HudRenderContext.TEXT;
        displayText = String.format(
                Locale.ROOT,
                "Health: %.1f%s  Armor: %d",
                health,
                absorption > 0.0f ? String.format(Locale.ROOT, " + %.1f", absorption) : "",
                armor
        );
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(displayText, x, y, displayColor);
        return 10;
    }
}
