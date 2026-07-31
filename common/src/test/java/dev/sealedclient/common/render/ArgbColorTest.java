package dev.sealedclient.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArgbColorTest {
    @Test
    void channelsAreUnpackedInTheRightOrder() {
        int argb = 0x80FF8040;
        assertEquals(1.0f, ArgbColor.red(argb), 1.0e-6f);
        assertEquals(0x80 / 255.0f, ArgbColor.green(argb), 1.0e-6f);
        assertEquals(0x40 / 255.0f, ArgbColor.blue(argb), 1.0e-6f);
        assertEquals(0x80 / 255.0f, ArgbColor.alpha(argb, 1.0f), 1.0e-6f);
    }

    @Test
    void blackAndWhiteRoundTrip() {
        assertEquals(0.0f, ArgbColor.red(0xFF000000), 1.0e-6f);
        assertEquals(1.0f, ArgbColor.red(0xFFFFFFFF), 1.0e-6f);
        assertEquals(1.0f, ArgbColor.blue(0xFFFFFFFF), 1.0e-6f);
    }

    @Test
    void alphaScalesDown() {
        assertEquals(0.5f, ArgbColor.alpha(0xFF000000, 0.5f), 1.0e-6f);
        assertEquals(0.0f, ArgbColor.alpha(0xFF000000, 0.0f), 1.0e-6f);
    }

    @Test
    void alphaCannotExceedFullyOpaque() {
        // Scaling past one used to wrap an almost-opaque overlay into an
        // invisible one, which reads as the overlay having failed to draw.
        assertEquals(1.0f, ArgbColor.alpha(0xFF000000, 4.0f), 1.0e-6f);
        assertEquals(1.0f, ArgbColor.alpha(0x80000000, 9.0f), 1.0e-6f);
    }

    @Test
    void alphaCannotGoNegative() {
        assertEquals(0.0f, ArgbColor.alpha(0xFF000000, -1.0f), 1.0e-6f);
    }

    @Test
    void nonsenseScalesDrawNothingRatherThanCoveringTheScreen() {
        // Both directions are treated as "no overlay". Clamping infinity to
        // fully opaque would paint the whole screen from a single bad setting,
        // which is far worse to look at than a missing overlay.
        assertEquals(0.0f, ArgbColor.alpha(0xFF000000, Float.NaN), 1.0e-6f);
        assertEquals(0.0f, ArgbColor.alpha(0xFF000000, Float.POSITIVE_INFINITY), 1.0e-6f);
        assertEquals(0.0f, ArgbColor.alpha(0xFF000000, Float.NEGATIVE_INFINITY), 1.0e-6f);
    }

    @Test
    void opaqueSetsAlphaWithoutTouchingColour() {
        int result = ArgbColor.opaque(0x00123456);
        assertEquals(0xFF123456, result);
        assertEquals(ArgbColor.red(0x00123456), ArgbColor.red(result), 1.0e-6f);
        assertEquals(ArgbColor.green(0x00123456), ArgbColor.green(result), 1.0e-6f);
        assertEquals(ArgbColor.blue(0x00123456), ArgbColor.blue(result), 1.0e-6f);
    }

    @Test
    void everyChannelStaysInsideTheVisibleRange() {
        for (int probe = 0; probe < 256; probe++) {
            int argb = probe << 24 | probe << 16 | probe << 8 | probe;
            assertTrue(inRange(ArgbColor.red(argb)), "red at " + probe);
            assertTrue(inRange(ArgbColor.green(argb)), "green at " + probe);
            assertTrue(inRange(ArgbColor.blue(argb)), "blue at " + probe);
            assertTrue(inRange(ArgbColor.alpha(argb, 2.0f)), "alpha at " + probe);
        }
    }

    private static boolean inRange(float value) {
        return value >= 0.0f && value <= 1.0f;
    }
}
