package dev.sealedclient.hud;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;

public abstract class HudModule extends Module {
    public static final int DEFAULT_X = 6;
    public static final int DEFAULT_Y = 6;

    private static final int MAXIMUM_COORDINATE = 8192;

    private final BooleanSetting customPosition;
    private final IntegerSetting layoutX;
    private final IntegerSetting layoutY;

    protected HudModule(
            String id,
            String name,
            String description,
            boolean defaultEnabled
    ) {
        super(id, name, description, Category.HUD, defaultEnabled);
        customPosition = addSetting(new BooleanSetting(
                "hud_custom_position",
                "Custom HUD position",
                "Whether this element uses a position from the HUD editor.",
                false
        ));
        layoutX = addSetting(new IntegerSetting(
                "hud_x",
                "HUD X",
                "Horizontal position assigned by the HUD editor.",
                DEFAULT_X,
                0,
                MAXIMUM_COORDINATE,
                1
        ));
        layoutY = addSetting(new IntegerSetting(
                "hud_y",
                "HUD Y",
                "Vertical position assigned by the HUD editor.",
                DEFAULT_Y,
                0,
                MAXIMUM_COORDINATE,
                1
        ));

        // Layout state is edited visually, not mixed into each module's normal controls.
        customPosition.visibleWhen(() -> false);
        layoutX.visibleWhen(() -> false);
        layoutY.visibleWhen(() -> false);
    }

    public final boolean hasCustomPosition() {
        return customPosition.get();
    }

    public final int layoutX() {
        return layoutX.get();
    }

    public final int layoutY() {
        return layoutY.get();
    }

    /**
     * Moves this element out of the default stack and into an editor-controlled position.
     */
    public final void setLayoutPosition(int x, int y) {
        layoutX.set(x);
        layoutY.set(y);
        customPosition.set(true);
    }

    /**
     * Returns this element to its original automatic stack position.
     */
    public final void resetLayoutPosition() {
        customPosition.set(false);
        layoutX.reset();
        layoutY.reset();
    }

    /**
     * Renders this element and returns the vertical space consumed.
     */
    public abstract int render(HudRenderContext context, int x, int y);
}
