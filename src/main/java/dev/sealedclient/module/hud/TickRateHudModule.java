package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.Locale;

public final class TickRateHudModule extends HudModule implements TickableModule {
    private static final double SMOOTHING = 0.15;
    private static final long MAX_SAMPLE_NANOS = 2_000_000_000L;

    private long previousTickNanos;
    private double averageTickMillis;
    private String displayText;

    public TickRateHudModule() {
        super(
                "tick_rate",
                "Tick Rate",
                "Shows measured client tick timing and an estimated network round trip.",
                false
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        long now = System.nanoTime();
        if (minecraft.player == null) {
            reset();
            return;
        }

        if (previousTickNanos != 0L) {
            long elapsed = now - previousTickNanos;
            if (elapsed > 0L && elapsed <= MAX_SAMPLE_NANOS) {
                double sampleMillis = elapsed / 1_000_000.0;
                averageTickMillis = averageTickMillis == 0.0
                        ? sampleMillis
                        : averageTickMillis + SMOOTHING * (sampleMillis - averageTickMillis);
            }
        }
        previousTickNanos = now;

        int ping = currentPing(minecraft);
        if (averageTickMillis <= 0.0) {
            displayText = "Client tick: measuring  |  Net RTT: "
                    + (ping < 0 ? "?" : "~" + ping + " ms");
            return;
        }

        double clientTicksPerSecond = Math.min(20.0, 1_000.0 / averageTickMillis);
        displayText = String.format(
                Locale.ROOT,
                "Client tick: %.1f ms (%.1f TPS)  Net RTT: %s",
                averageTickMillis,
                clientTicksPerSecond,
                ping < 0 ? "?" : "~" + ping + " ms"
        );
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        reset();
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(displayText, x, y, HudRenderContext.TEXT);
        return 10;
    }

    private static int currentPing(Minecraft minecraft) {
        if (minecraft.getConnection() == null || minecraft.player == null) {
            return -1;
        }
        PlayerInfo info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    private void reset() {
        previousTickNanos = 0L;
        averageTickMillis = 0.0;
        displayText = null;
    }
}
