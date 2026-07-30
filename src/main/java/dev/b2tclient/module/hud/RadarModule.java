package dev.b2tclient.module.hud;

import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RadarModule extends HudModule implements TickableModule {
    private final IntegerSetting range = addSetting(new IntegerSetting(
            "range",
            "Range",
            "Maximum player distance to display.",
            128,
            16,
            512,
            16
    ));

    private final IntegerSetting maximum = addSetting(new IntegerSetting(
            "maximum",
            "Maximum",
            "Maximum nearby players to display.",
            6,
            1,
            16,
            1
    ));
    private final List<AbstractClientPlayer> nearbyPlayers = new ArrayList<>();
    private final List<String> displayLines = new ArrayList<>();
    private LocalPlayer sortOrigin;
    private final Comparator<AbstractClientPlayer> distanceOrder =
            Comparator.comparingDouble(player -> sortOrigin.distanceToSqr(player));

    public RadarModule() {
        super("radar", "Player Radar", "Lists nearby visible players by distance.", false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer localPlayer = minecraft.player;
        nearbyPlayers.clear();
        displayLines.clear();
        if (localPlayer == null || minecraft.level == null) {
            return;
        }

        double maximumDistanceSquared = range.get() * (double) range.get();
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (player != localPlayer
                    && !player.isSpectator()
                    && player.isAlive()
                    && localPlayer.distanceToSqr(player) <= maximumDistanceSquared) {
                nearbyPlayers.add(player);
            }
        }
        sortOrigin = localPlayer;
        nearbyPlayers.sort(distanceOrder);
        sortOrigin = null;

        int limit = Math.min(maximum.get(), nearbyPlayers.size());
        for (int index = 0; index < limit; index++) {
            AbstractClientPlayer player = nearbyPlayers.get(index);
            int distance = (int) Math.round(localPlayer.distanceTo(player));
            displayLines.add(player.getName().getString() + "  " + distance + "m");
        }
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayLines.isEmpty()) {
            return 0;
        }
        context.text("Nearby players:", x, y, HudRenderContext.MUTED);
        for (int line = 0; line < displayLines.size(); line++) {
            context.text(
                    displayLines.get(line),
                    x,
                    y + (line + 1) * 10,
                    HudRenderContext.TEXT
            );
        }
        return (displayLines.size() + 1) * 10;
    }
}
