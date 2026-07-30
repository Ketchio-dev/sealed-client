package dev.b2tclient.v26.visual;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualOverlayConfiguration26Test {
    @Test
    void disabledConfigurationIsCoherentAndPassive() {
        var configuration = VisualOverlayConfiguration26.DISABLED;

        assertFalse(configuration.anyEnabled());
        assertFalse(configuration.anyEntityOverlayEnabled());
        assertFalse(configuration.anyScanOverlayEnabled());
    }

    @Test
    void targetIdentifiersAreDefensivelyCopied() {
        var mutable = new HashSet<String>();
        mutable.add("minecraft:ancient_debris");
        var blockEsp = new VisualOverlayConfiguration26.BlockEsp(
                true,
                mutable,
                64,
                2_048,
                0xFFFFFFFF,
                512
        );

        mutable.clear();

        assertTrue(blockEsp.targets().contains("minecraft:ancient_debris"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> blockEsp.targets().clear()
        );
    }

    @Test
    void rejectsUnboundedWorkSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VisualOverlayConfiguration26.HoleEsp(
                        true,
                        24,
                        0,
                        0,
                        0,
                        true,
                        VisualOverlayConfiguration26.MAX_SCAN_BUDGET + 1,
                        1_024
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new VisualOverlayConfiguration26.Tracers(
                        true,
                        192.0,
                        0,
                        0,
                        true,
                        false,
                        5.0F,
                        128
                )
        );
    }
}
