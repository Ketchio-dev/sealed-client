package dev.b2tclient.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Objects;

public record HudRenderContext(Minecraft minecraft, GuiGraphics graphics) {
    public static final int TEXT = 0xFFF2F2F2;
    public static final int MUTED = 0xFFAAAAAA;
    public static final int ACCENT = 0xFF55D6BE;
    public static final int WARNING = 0xFFFF6B6B;

    public HudRenderContext {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(graphics, "graphics");
    }

    public void text(String value, int x, int y, int color) {
        graphics.drawString(minecraft.font, value, x, y, color, true);
    }

    public String labelValue(String label, String value) {
        return label + ": " + value;
    }
}

