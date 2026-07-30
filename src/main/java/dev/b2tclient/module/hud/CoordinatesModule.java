package dev.b2tclient.module.hud;

import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

public final class CoordinatesModule extends HudModule implements TickableModule {
    private final BooleanSetting dimensionalCoordinates = addSetting(new BooleanSetting(
            "dimensional_coordinates",
            "Dimension conversion",
            "Show the corresponding Overworld or Nether X/Z coordinates.",
            true
    ));
    private String displayText;
    private double displayedX = Double.NaN;
    private double displayedY = Double.NaN;
    private double displayedZ = Double.NaN;
    private boolean displayedConversion;
    private ResourceKey<Level> displayedDimension;

    public CoordinatesModule() {
        super("coordinates", "Coordinates", "Displays precise player coordinates.", true);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            displayText = null;
            return;
        }

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        boolean conversion = dimensionalCoordinates.get();
        ResourceKey<Level> dimension = player.level().dimension();
        if (displayText != null
                && displayedX == x
                && displayedY == y
                && displayedZ == z
                && displayedConversion == conversion
                && dimension.equals(displayedDimension)) {
            return;
        }
        displayedX = x;
        displayedY = y;
        displayedZ = z;
        displayedConversion = conversion;
        displayedDimension = dimension;
        displayText = String.format(
                Locale.ROOT,
                "XYZ: %.1f / %.1f / %.1f",
                x,
                y,
                z
        );

        if (conversion && (dimension == Level.NETHER || dimension == Level.OVERWORLD)) {
            double scale = dimension == Level.NETHER ? 8.0 : 0.125;
            displayText += String.format(
                    Locale.ROOT,
                    "  [%.0f, %.0f]",
                    x * scale,
                    z * scale
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
