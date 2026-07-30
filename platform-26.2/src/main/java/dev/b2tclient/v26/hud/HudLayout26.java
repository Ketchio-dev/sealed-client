package dev.b2tclient.v26.hud;

import java.util.EnumMap;
import java.util.Map;

/**
 * Where each HUD panel sits, stored as a fraction of the screen.
 *
 * <p>Fractions rather than pixels keep a layout meaningful after a resolution
 * change, and every resolve step re-clamps against the panel's measured size so
 * a panel can never render partly off-screen. When a panel is larger than the
 * axis it is being placed on — a tall stat column on a short window, or a GUI
 * scale the user raised past what fits — the panel is pinned to the origin so
 * the top-left of the content stays readable instead of the middle.</p>
 */
public final class HudLayout26 {
    private final Map<Panel, Anchor> anchors = new EnumMap<>(Panel.class);

    public HudLayout26() {
        reset();
    }

    /** Restores every panel to its vanilla-style default corner. */
    public void reset() {
        for (Panel panel : Panel.values()) {
            anchors.put(panel, panel.defaultAnchor());
        }
    }

    public Anchor anchor(Panel panel) {
        return anchors.getOrDefault(panel, panel.defaultAnchor());
    }

    public void setAnchor(Panel panel, Anchor anchor) {
        anchors.put(panel, Anchor.clampFractions(anchor));
    }

    /** True when every panel is still at its default position. */
    public boolean isDefault() {
        for (Panel panel : Panel.values()) {
            if (!anchor(panel).equals(panel.defaultAnchor())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a panel to on-screen pixels, guaranteeing it stays fully visible.
     */
    public Position resolve(
            Panel panel,
            int panelWidth,
            int panelHeight,
            int screenWidth,
            int screenHeight
    ) {
        Anchor anchor = anchor(panel);
        return new Position(
                resolveAxis(anchor.xFraction(), panelWidth, screenWidth),
                resolveAxis(anchor.yFraction(), panelHeight, screenHeight)
        );
    }

    /**
     * Converts a dragged pixel position back into a stored fraction.
     *
     * <p>The pixel is clamped first, so a drag that leaves the window cannot
     * persist an off-screen anchor.</p>
     */
    public static Anchor anchorFor(
            int pixelX,
            int pixelY,
            int panelWidth,
            int panelHeight,
            int screenWidth,
            int screenHeight
    ) {
        return new Anchor(
                fractionForAxis(pixelX, panelWidth, screenWidth),
                fractionForAxis(pixelY, panelHeight, screenHeight)
        );
    }

    /**
     * Places a panel along one axis, never allowing it to overflow the screen.
     */
    public static int resolveAxis(double fraction, int panelSize, int screenSize) {
        int travel = screenSize - Math.max(0, panelSize);
        if (travel <= 0) {
            // The panel does not fit; pinning to 0 keeps its start visible.
            return 0;
        }
        double safeFraction = Double.isFinite(fraction)
                ? Math.max(0.0, Math.min(1.0, fraction))
                : 0.0;
        long pixel = Math.round(safeFraction * travel);
        return (int) Math.max(0, Math.min(travel, pixel));
    }

    private static double fractionForAxis(int pixel, int panelSize, int screenSize) {
        int travel = screenSize - Math.max(0, panelSize);
        if (travel <= 0) {
            return 0.0;
        }
        int clamped = Math.max(0, Math.min(travel, pixel));
        return (double) clamped / travel;
    }

    public Map<Panel, Anchor> snapshot() {
        return Map.copyOf(anchors);
    }

    public void applySnapshot(Map<Panel, Anchor> snapshot) {
        reset();
        if (snapshot == null) {
            return;
        }
        snapshot.forEach((panel, anchor) -> {
            if (panel != null && anchor != null) {
                anchors.put(panel, Anchor.clampFractions(anchor));
            }
        });
    }

    /** A draggable HUD block. */
    public enum Panel {
        /** The stacked stat readout (coordinates, FPS, ping, ...). */
        INFO("Info", 0.0, 0.0),
        /** The list of currently enabled modules. */
        ARRAY_LIST("Module list", 1.0, 0.0);

        private final String label;
        private final double defaultX;
        private final double defaultY;

        Panel(String label, double defaultX, double defaultY) {
            this.label = label;
            this.defaultX = defaultX;
            this.defaultY = defaultY;
        }

        public String label() {
            return label;
        }

        public Anchor defaultAnchor() {
            return new Anchor(defaultX, defaultY);
        }

        public static Panel byId(String id) {
            for (Panel panel : values()) {
                if (panel.name().equalsIgnoreCase(id)) {
                    return panel;
                }
            }
            return null;
        }
    }

    /** A panel position as a fraction of the available travel on each axis. */
    public record Anchor(double xFraction, double yFraction) {
        public static Anchor clampFractions(Anchor anchor) {
            if (anchor == null) {
                return new Anchor(0.0, 0.0);
            }
            return new Anchor(clamp(anchor.xFraction()), clamp(anchor.yFraction()));
        }

        private static double clamp(double value) {
            if (!Double.isFinite(value)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    /** A resolved on-screen pixel position. */
    public record Position(int x, int y) {
    }
}
