package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import java.util.Locale;

public final class DirectionModule extends HudModule implements TickableModule {
    private String displayText;
    private float displayedYaw = Float.NaN;

    public DirectionModule() {
        super("direction", "Direction", "Displays facing direction and yaw.", true);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            displayText = null;
            return;
        }

        float yaw = player.getYRot();
        if (displayText != null && displayedYaw == yaw) {
            return;
        }
        displayedYaw = yaw;
        Direction direction = Direction.fromYRot(yaw);
        displayText = String.format(
                Locale.ROOT,
                "Facing: %s (%.0f\u00b0)",
                direction.getName().toUpperCase(Locale.ROOT),
                Mth.wrapDegrees(yaw)
        );
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
