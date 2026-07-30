package dev.b2tclient.v26.visual;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualOverlayService26Test {
    @Test
    void startsDisabledWithAnEmptyImmutableFrame() {
        var service = new VisualOverlayService26(
                (uuid, name) -> false
        );

        assertFalse(service.initialized());
        assertEquals(
                VisualOverlayConfiguration26.DISABLED,
                service.configuration()
        );
        assertTrue(service.extractedFrame().empty());
    }

    @Test
    void configurationSwapAndResetAreSafeBeforeRegistration() {
        var service = new VisualOverlayService26(
                (uuid, name) -> false
        );
        var disabled = VisualOverlayConfiguration26.DISABLED;

        service.setConfiguration(disabled);
        service.reset();

        assertEquals(disabled, service.configuration());
        assertTrue(service.extractedFrame().empty());
    }

    @Test
    void blockKeyRoundTripsCoordinates() {
        var key = new VisualOverlayService26.BlockKey(-12, 70, 44);

        assertEquals(key.x(), key.blockPos().getX());
        assertEquals(key.y(), key.blockPos().getY());
        assertEquals(key.z(), key.blockPos().getZ());
    }

    @Test
    void sharedProbeAllocatorNeverExceedsTheGlobalTickCeiling() {
        var disabled = VisualOverlayConfiguration26.DISABLED;
        var service = new VisualOverlayService26(
                (uuid, name) -> false
        );
        service.setConfiguration(new VisualOverlayConfiguration26(
                disabled.playerEsp(),
                disabled.tracers(),
                disabled.nametags(),
                disabled.storageEsp(),
                new VisualOverlayConfiguration26.HoleEsp(
                        true,
                        64,
                        0,
                        0,
                        0,
                        true,
                        VisualOverlayConfiguration26.MAX_SCAN_BUDGET,
                        1_024
                ),
                new VisualOverlayConfiguration26.BlockEsp(
                        true,
                        Set.of("minecraft:ancient_debris"),
                        192,
                        VisualOverlayConfiguration26.MAX_SCAN_BUDGET,
                        0,
                        2_560
                ),
                disabled.trajectories()
        ));

        assertEquals(
                VisualOverlayService26.MAX_COMBINED_BLOCK_PROBES_PER_TICK,
                service.budgets().combinedBlockProbesPerTick()
        );
        assertTrue(
                service.budgets().blockAdmissionsPerTick()
                        <= 32_768
        );
        assertTrue(
                service.budgets().holeAdmissionsPerTick()
                        <= 32_768
        );
    }

    @Test
    void nearFirstVolumeMappingIsCompleteUniqueAndBounded() {
        int horizontal = 4;
        int vertical = 2;
        long volume = 9L * 9L * 5L;
        Set<VisualOverlayService26.Offset> offsets = new HashSet<>();
        int previousRadius = -1;

        for (long index = 0; index < volume; index++) {
            VisualOverlayService26.Offset offset =
                    VisualOverlayService26.nearFirstOffset(
                            index,
                            horizontal,
                            vertical
                    );
            assertTrue(Math.abs(offset.x()) <= horizontal);
            assertTrue(Math.abs(offset.y()) <= vertical);
            assertTrue(Math.abs(offset.z()) <= horizontal);
            assertTrue(offsets.add(offset));

            int radius = Math.max(
                    Math.max(Math.abs(offset.x()), Math.abs(offset.z())),
                    Math.abs(offset.y())
            );
            assertTrue(radius >= previousRadius);
            previousRadius = radius;
        }

        assertEquals(volume, offsets.size());
        assertEquals(
                new VisualOverlayService26.Offset(0, 0, 0),
                VisualOverlayService26.nearFirstOffset(
                        0,
                        horizontal,
                        vertical
                )
        );
    }

    @Test
    void firstShellPrioritizesTheObserversHorizontalPlane() {
        for (long index = 1; index <= 8; index++) {
            assertEquals(
                    0,
                    VisualOverlayService26.nearFirstOffset(index, 4, 4).y()
            );
        }
    }
}
