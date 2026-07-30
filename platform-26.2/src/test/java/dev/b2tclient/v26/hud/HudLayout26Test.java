package dev.b2tclient.v26.hud;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudLayout26Test {
    @Test
    void defaultsPlaceInfoTopLeftAndModuleListTopRight() {
        HudLayout26 layout = new HudLayout26();
        assertTrue(layout.isDefault());

        HudLayout26.Position info = layout.resolve(
                HudLayout26.Panel.INFO, 100, 50, 640, 360);
        assertEquals(0, info.x());
        assertEquals(0, info.y());

        HudLayout26.Position list = layout.resolve(
                HudLayout26.Panel.ARRAY_LIST, 100, 50, 640, 360);
        assertEquals(540, list.x(), "right edge = screen width - panel width");
        assertEquals(0, list.y());
    }

    @Test
    void aPanelIsNeverPlacedPartlyOffScreen() {
        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(1.0, 1.0));

        HudLayout26.Position position = layout.resolve(
                HudLayout26.Panel.INFO, 100, 50, 320, 200);
        assertEquals(220, position.x());
        assertEquals(150, position.y());
        assertTrue(position.x() + 100 <= 320);
        assertTrue(position.y() + 50 <= 200);
    }

    @Test
    void aPanelTallerThanTheScreenPinsToTheOriginInsteadOfClipping() {
        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(1.0, 1.0));

        HudLayout26.Position position = layout.resolve(
                HudLayout26.Panel.INFO, 400, 500, 320, 200);
        assertEquals(0, position.x(), "an oversized panel keeps its left edge visible");
        assertEquals(0, position.y(), "an oversized panel keeps its top edge visible");
    }

    @Test
    void outOfRangeAndNonFiniteFractionsAreClamped() {
        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(5.0, -3.0));
        assertEquals(1.0, layout.anchor(HudLayout26.Panel.INFO).xFraction());
        assertEquals(0.0, layout.anchor(HudLayout26.Panel.INFO).yFraction());

        layout.setAnchor(HudLayout26.Panel.INFO,
                new HudLayout26.Anchor(Double.NaN, Double.POSITIVE_INFINITY));
        assertEquals(0.0, layout.anchor(HudLayout26.Panel.INFO).xFraction());
        assertEquals(0.0, layout.anchor(HudLayout26.Panel.INFO).yFraction());
    }

    @Test
    void aDragBeyondTheWindowStillLandsInsideIt() {
        HudLayout26.Anchor anchor = HudLayout26.anchorFor(
                -500, 9_000, 100, 50, 320, 200);
        assertEquals(0.0, anchor.xFraction());
        assertEquals(1.0, anchor.yFraction());

        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.INFO, anchor);
        HudLayout26.Position position = layout.resolve(
                HudLayout26.Panel.INFO, 100, 50, 320, 200);
        assertEquals(0, position.x());
        assertEquals(150, position.y());
    }

    @Test
    void dragAndResolveRoundTripToTheSamePixel() {
        HudLayout26 layout = new HudLayout26();
        for (int pixel : new int[] {0, 37, 111, 220}) {
            layout.setAnchor(HudLayout26.Panel.INFO,
                    HudLayout26.anchorFor(pixel, 0, 100, 50, 320, 200));
            assertEquals(
                    pixel,
                    layout.resolve(HudLayout26.Panel.INFO, 100, 50, 320, 200).x(),
                    "placing at " + pixel + " must resolve back to it"
            );
        }
    }

    @Test
    void resetRestoresEveryPanelAndReportsDefault() {
        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(0.5, 0.5));
        layout.setAnchor(HudLayout26.Panel.ARRAY_LIST, new HudLayout26.Anchor(0.25, 0.75));
        assertFalse(layout.isDefault());

        layout.reset();
        assertTrue(layout.isDefault());
        assertEquals(HudLayout26.Panel.INFO.defaultAnchor(),
                layout.anchor(HudLayout26.Panel.INFO));
        assertEquals(HudLayout26.Panel.ARRAY_LIST.defaultAnchor(),
                layout.anchor(HudLayout26.Panel.ARRAY_LIST));
    }

    @Test
    void applySnapshotIgnoresNullsAndRestoresMissingPanelsToDefaults() {
        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.ARRAY_LIST, new HudLayout26.Anchor(0.3, 0.3));

        layout.applySnapshot(Map.of(
                HudLayout26.Panel.INFO, new HudLayout26.Anchor(0.5, 0.5)
        ));

        assertEquals(0.5, layout.anchor(HudLayout26.Panel.INFO).xFraction());
        assertEquals(
                HudLayout26.Panel.ARRAY_LIST.defaultAnchor(),
                layout.anchor(HudLayout26.Panel.ARRAY_LIST),
                "a panel absent from the snapshot returns to its default"
        );

        layout.applySnapshot(null);
        assertTrue(layout.isDefault());
    }

    @Test
    void panelIdsResolveCaseInsensitivelyAndRejectUnknownNames() {
        assertEquals(HudLayout26.Panel.INFO, HudLayout26.Panel.byId("info"));
        assertEquals(HudLayout26.Panel.ARRAY_LIST, HudLayout26.Panel.byId("ARRAY_LIST"));
        assertEquals(null, HudLayout26.Panel.byId("not_a_panel"));
    }
}
