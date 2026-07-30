package dev.b2tclient.v26.visual;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualOverlayRenderer26Test {
    @Test
    void snapshotIsImmutableAndReportsEmptyState() {
        var snapshot = new VisualOverlayRenderer26.FrameSnapshot(
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(snapshot.empty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.lines().add(new VisualOverlayRenderer26.Line(
                        new VisualOverlayRenderer26.Point(0, 0, 0),
                        new VisualOverlayRenderer26.Point(1, 1, 1),
                        0xFFFFFFFF,
                        1.0F
                ))
        );
    }

    @Test
    void hardFrameLimitsRejectUnboundedGeometry() {
        var point = new VisualOverlayRenderer26.Point(0, 0, 0);
        var line = new VisualOverlayRenderer26.Line(
                point,
                new VisualOverlayRenderer26.Point(1, 0, 0),
                0xFFFFFFFF,
                1.0F
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new VisualOverlayRenderer26.FrameSnapshot(
                        List.of(),
                        Collections.nCopies(
                                VisualOverlayRenderer26.MAX_LINES_PER_FRAME + 1,
                                line
                        ),
                        List.of()
                )
        );
    }

    @Test
    void validatesGeometryAndLabels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VisualOverlayRenderer26.Box(
                        new VisualOverlayRenderer26.Point(1, 0, 0),
                        new VisualOverlayRenderer26.Point(0, 1, 1),
                        0,
                        1.0F
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new VisualOverlayRenderer26.Label(
                        new VisualOverlayRenderer26.Point(0, 0, 0),
                        Component.literal("test"),
                        16,
                        0xFFFFFFFF,
                        0,
                        3.0F
                )
        );
    }

    @Test
    void geometryDepthPolicyDefaultsToEspThroughWalls() {
        var origin = new VisualOverlayRenderer26.Point(0, 0, 0);
        var target = new VisualOverlayRenderer26.Point(1, 1, 1);
        var espBox = new VisualOverlayRenderer26.Box(
                origin,
                target,
                0xFFFFFFFF,
                0.25F
        );
        var impactBox = new VisualOverlayRenderer26.Box(
                origin,
                target,
                0xFFFFFFFF,
                0.25F,
                false
        );
        var espLine = new VisualOverlayRenderer26.Line(
                origin,
                target,
                0xFFFFFFFF,
                1.0F
        );
        var trajectoryLine = new VisualOverlayRenderer26.Line(
                origin,
                target,
                0xFFFFFFFF,
                1.0F,
                false
        );

        assertTrue(espBox.throughWalls());
        assertFalse(impactBox.throughWalls());
        assertTrue(espLine.throughWalls());
        assertFalse(trajectoryLine.throughWalls());
    }
}
