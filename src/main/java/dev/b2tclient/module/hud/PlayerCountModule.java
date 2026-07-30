package dev.b2tclient.module.hud;

import dev.b2tclient.core.TickableModule;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;

public final class PlayerCountModule extends HudModule implements TickableModule {
    private String displayText;
    private int displayedCount = -1;

    public PlayerCountModule() {
        super("player_count", "Player Count", "Displays players visible in the tab list.", false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.getConnection() == null) {
            displayText = null;
            return;
        }
        int count = minecraft.getConnection().getOnlinePlayers().size();
        if (displayText == null || displayedCount != count) {
            displayedCount = count;
            displayText = "Players: " + count;
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
