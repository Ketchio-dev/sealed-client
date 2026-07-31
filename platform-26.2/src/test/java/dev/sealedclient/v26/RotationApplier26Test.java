package dev.sealedclient.v26;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RotationApplier26Test {
    @Test
    void aimIsIgnoredWithoutAPlayer() {
        RotationApplier26 applier = new RotationApplier26();
        applier.beginTick();

        assertFalse(applier.request(null, "siege", 76, 90.0f, 0.0f));
        assertFalse(applier.intervening());
    }

    @Test
    void endTickWithoutAPlayerIsSafe() {
        RotationApplier26 applier = new RotationApplier26();
        applier.beginTick();

        applier.endTick(null);

        assertFalse(applier.intervening());
    }

    @Test
    void resetClearsInterventionState() {
        RotationApplier26 applier = new RotationApplier26();
        applier.beginTick();
        applier.request(null, "siege", 76, 90.0f, 0.0f);

        applier.reset();

        assertFalse(applier.intervening());
    }

    @Test
    void turnRateMatchesThe121Default() {
        assertEquals(180.0f, new RotationApplier26().degreesPerTick(), 1.0e-4f);
    }

    @Test
    void turnRateIsConfigurable() {
        RotationApplier26 applier = new RotationApplier26();
        applier.setDegreesPerTick(45.0f);
        assertEquals(45.0f, applier.degreesPerTick(), 1.0e-4f);
    }
}
