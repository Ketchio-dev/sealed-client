package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;

import java.util.Locale;

public final class SpeedModule extends HudModule implements TickableModule {
    private boolean initialized;
    private double lastX;
    private double lastZ;
    private double blocksPerSecond;
    private String displayText;
    private long displayedHundredths = Long.MIN_VALUE;

    public SpeedModule() {
        super("speed", "Speed", "Displays horizontal movement speed.", true);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null) {
            initialized = false;
            blocksPerSecond = 0.0;
            displayText = null;
            return;
        }

        double x = minecraft.player.getX();
        double z = minecraft.player.getZ();
        if (initialized) {
            blocksPerSecond = Math.hypot(x - lastX, z - lastZ) * 20.0;
        }
        lastX = x;
        lastZ = z;
        initialized = true;
        long hundredths = Math.round(blocksPerSecond * 100.0);
        if (displayText == null || displayedHundredths != hundredths) {
            displayedHundredths = hundredths;
            displayText = String.format(
                    Locale.ROOT,
                    "Speed: %.2f m/s",
                    hundredths / 100.0
            );
        }
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(displayText, x, y, HudRenderContext.TEXT);
        return 10;
    }
}
