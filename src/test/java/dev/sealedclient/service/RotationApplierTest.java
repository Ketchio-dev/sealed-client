package dev.sealedclient.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RotationApplierTest {
    @Test
    void aimIsIgnoredWithoutAPlayer() {
        RotationApplier applier = new RotationApplier();
        applier.beginTick();

        assertFalse(applier.request(null, "kill_aura", 60, 90.0f, 0.0f));
        assertFalse(applier.intervening(), "Nothing may be restored if nothing was ever aimed");
    }

    @Test
    void endTickWithoutAPlayerIsSafe() {
        RotationApplier applier = new RotationApplier();
        applier.beginTick();

        applier.endTick(null);

        assertFalse(applier.intervening());
    }

    @Test
    void resetClearsInterventionState() {
        RotationApplier applier = new RotationApplier();
        applier.beginTick();
        applier.request(null, "kill_aura", 60, 90.0f, 0.0f);

        applier.reset();

        assertFalse(applier.intervening());
    }

    @Test
    void turnRateDefaultsToUnlimitedSoAimIsNotSlowedByDefault() {
        assertEquals(180.0f, new RotationApplier().degreesPerTick(), 1.0e-4f);
    }

    @Test
    void turnRateIsConfigurable() {
        RotationApplier applier = new RotationApplier();
        applier.setDegreesPerTick(30.0f);
        assertEquals(30.0f, applier.degreesPerTick(), 1.0e-4f);
    }
}
