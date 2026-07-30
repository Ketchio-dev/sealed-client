package dev.b2tclient.module.visual;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.setting.ColorSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.IntegerSetting;

/**
 * Configures the client-side projectile preview rendered by the world renderer.
 *
 * <p>The module deliberately contains no tick logic. Keeping simulation and
 * drawing in the renderer avoids duplicating world state and makes disabling
 * the module immediately stop all trajectory work.</p>
 */
public final class TrajectoriesModule extends Module {
    private final DoubleSetting range = addSetting(new DoubleSetting(
            "range",
            "Range",
            "Maximum distance, in blocks, simulated for a projectile preview.",
            96.0,
            16.0,
            256.0,
            4.0
    ));
    private final IntegerSetting steps = addSetting(new IntegerSetting(
            "steps",
            "Simulation Steps",
            "Maximum number of trajectory simulation steps per frame.",
            120,
            20,
            320,
            10
    ));
    private final ColorSetting color = addSetting(new ColorSetting(
            "color",
            "Line Color",
            "ARGB color used for the projected path.",
            0xE65AE6FF
    ));

    public TrajectoriesModule() {
        super(
                "trajectories",
                "Trajectories",
                "Previews the client-side flight path of supported projectiles.",
                Category.VISUAL,
                false
        );
    }

    public double range() {
        return range.get();
    }

    public int steps() {
        return steps.get();
    }

    public int color() {
        return color.get();
    }
}
