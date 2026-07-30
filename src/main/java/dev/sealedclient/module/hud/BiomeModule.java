package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public final class BiomeModule extends HudModule implements TickableModule {
    private String displayText;
    private Level displayedLevel;
    private int displayedX;
    private int displayedY;
    private int displayedZ;

    public BiomeModule() {
        super("biome", "Biome", "Displays the current biome identifier.", false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            displayText = null;
            return;
        }

        int blockX = Mth.floor(player.getX());
        int blockY = Mth.floor(player.getY());
        int blockZ = Mth.floor(player.getZ());
        if (displayText != null
                && displayedLevel == player.level()
                && displayedX == blockX
                && displayedY == blockY
                && displayedZ == blockZ) {
            return;
        }
        displayedLevel = player.level();
        displayedX = blockX;
        displayedY = blockY;
        displayedZ = blockZ;
        String biome = player.level()
                .getBiome(player.blockPosition())
                .unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("unknown");
        displayText = "Biome: " + biome;
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
