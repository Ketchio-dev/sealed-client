package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

public final class PingModule extends HudModule implements TickableModule {
    private String displayText;
    private int displayedLatency = Integer.MIN_VALUE;

    public PingModule() {
        super("ping", "Ping", "Displays server latency.", true);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            displayText = null;
            return;
        }

        PlayerInfo info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        int latency = info == null ? -1 : info.getLatency();
        if (displayText == null || displayedLatency != latency) {
            displayedLatency = latency;
            displayText = "Ping: " + (latency < 0 ? "?" : latency + " ms");
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
