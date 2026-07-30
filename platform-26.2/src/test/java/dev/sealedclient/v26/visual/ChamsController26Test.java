package dev.sealedclient.v26.visual;

import com.mojang.blaze3d.platform.CompareOp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChamsController26Test {
    private final ChamsController26 controller = new ChamsController26();

    @AfterEach
    void disableHooks() {
        controller.release();
    }

    @Test
    void packedColorMultiplicationIncludesAlpha() {
        assertEquals(
                0x40401004,
                ChamsController26.multiplyArgb(0x80804020, 0x80_804020)
        );
        assertEquals(
                0xA0FF5555,
                ChamsController26.multiplyArgb(
                        0xFFFFFFFF,
                        0xA0FF5555
                )
        );
    }

    @Test
    void throughWallPipelineAlwaysPassesAndNeverWritesDepth() {
        assertEquals(
                CompareOp.ALWAYS_PASS,
                ChamsController26.throughWallDepthState().depthTest()
        );
        assertFalse(ChamsController26.throughWallDepthState().writeDepth());
    }

    @Test
    void configurationRejectsInvisibleTint() {
        ChamsController26.Configuration configured =
                new ChamsController26.Configuration(0xD0123456, true);
        controller.setConfiguration(configured);

        assertEquals(configured, controller.configuration());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChamsController26.Configuration(0x00123456, false)
        );
        assertThrows(
                NullPointerException.class,
                () -> controller.setConfiguration(null)
        );
    }

    @Test
    void missingClientAndReleaseKeepHookDisabled() {
        controller.tick(null, true);
        assertFalse(ChamsController26.active());

        controller.release();
        assertFalse(ChamsController26.snapshot().enabled());
    }

    @Test
    void nullCollectorFailsClosed() {
        assertEquals(
                null,
                ChamsController26.wrapIfActive(null, null)
        );
    }
}
