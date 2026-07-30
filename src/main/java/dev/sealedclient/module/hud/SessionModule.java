package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

public final class SessionModule extends HudModule implements TickableModule {
    private long startedAtNanos;
    private double distance;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLastPosition;
    private ResourceKey<Level> lastDimension;
    private long displayedSecond = Long.MIN_VALUE;
    private long displayedDistanceTenth = Long.MIN_VALUE;
    private String displayText;

    public SessionModule() {
        super("session", "Session", "Displays session duration and distance travelled.", false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            resetSession();
            return;
        }

        if (startedAtNanos == 0L) {
            startedAtNanos = System.nanoTime();
            rememberPosition(player);
            lastDimension = player.level().dimension();
            updateDisplayText();
            return;
        }

        ResourceKey<Level> dimension = player.level().dimension();
        if (hasLastPosition && dimension.equals(lastDimension)) {
            double dx = player.getX() - lastX;
            double dy = player.getY() - lastY;
            double dz = player.getZ() - lastZ;
            double segment = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (segment <= 64.0) {
                distance += segment;
            }
        }
        rememberPosition(player);
        lastDimension = dimension;
        updateDisplayText();
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        resetSession();
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(displayText, x, y, HudRenderContext.TEXT);
        return 10;
    }

    private void rememberPosition(LocalPlayer player) {
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        hasLastPosition = true;
    }

    private void updateDisplayText() {
        long seconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
        long distanceTenth = Math.round(distance / 100.0);
        if (seconds == displayedSecond && distanceTenth == displayedDistanceTenth) {
            return;
        }
        displayedSecond = seconds;
        displayedDistanceTenth = distanceTenth;
        String duration = "%02d:%02d:%02d".formatted(
                seconds / 3600,
                seconds / 60 % 60,
                seconds % 60
        );
        displayText = String.format(
                Locale.ROOT,
                "Session: %s  Distance: %.1f km",
                duration,
                distanceTenth / 10.0
        );
    }

    private void resetSession() {
        startedAtNanos = 0L;
        distance = 0.0;
        hasLastPosition = false;
        lastDimension = null;
        displayedSecond = Long.MIN_VALUE;
        displayedDistanceTenth = Long.MIN_VALUE;
        displayText = null;
    }
}
