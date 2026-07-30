package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;

/**
 * Controls presentation of waypoints maintained by the shared waypoint
 * service. The world renderer owns all drawing and distance culling.
 */
public final class WaypointsModule extends Module {
    private final BooleanSetting labels = addSetting(new BooleanSetting(
            "labels",
            "Labels",
            "Shows waypoint names and distances in the world.",
            true
    ));
    private final BooleanSetting beams = addSetting(new BooleanSetting(
            "beams",
            "Beams",
            "Draws vertical beams at visible waypoint positions.",
            true
    ));
    private final IntegerSetting renderDistance = addSetting(new IntegerSetting(
            "render_distance",
            "Render Distance",
            "Maximum waypoint render distance in blocks.",
            2048,
            64,
            16384,
            64
    ));

    public WaypointsModule() {
        super(
                "waypoints",
                "Waypoints",
                "Displays saved waypoints as labels and optional world beams.",
                Category.VISUAL,
                false
        );
    }

    public boolean labels() {
        return labels.get();
    }

    public boolean beams() {
        return beams.get();
    }

    public int renderDistance() {
        return renderDistance.get();
    }
}
