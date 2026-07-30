package dev.b2tclient.hud;

import java.util.Objects;

/**
 * The most recently rendered, draggable area for one HUD element.
 */
public record HudElementBounds(
        HudModule module,
        int x,
        int y,
        int width,
        int height
) {
    public HudElementBounds {
        Objects.requireNonNull(module, "module");
        width = Math.max(1, width);
        height = Math.max(1, height);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x
                && mouseX < x + width
                && mouseY >= y
                && mouseY < y + height;
    }

    public int clampX(int requested, int screenWidth) {
        return clamp(requested, 2, Math.max(2, screenWidth - width - 2));
    }

    public int clampY(int requested, int screenHeight) {
        return clamp(requested, 2, Math.max(2, screenHeight - height - 2));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
