package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCraftAutomation26Test {
    @Test
    void predictionRequiresLatencyBufferedStableObservations() {
        int required = AutoCraftAutomation26.confirmationStabilityTicks(
                500,
                40
        );
        assertEquals(14, required);

        int stable = 0;
        assertFalse(stable >= required);
        for (int tick = 0; tick < required; tick++) {
            stable = AutoCraftAutomation26.nextStableObservationCount(
                    stable,
                    required,
                    true,
                    true
            );
        }
        assertTrue(stable >= required);
    }

    @Test
    void correctionInvalidatesAndSafetyPauseDoesNotAgeWindow() {
        int required = AutoCraftAutomation26.confirmationStabilityTicks(
                -1,
                40
        );
        assertEquals(24, required);
        assertEquals(
                3,
                AutoCraftAutomation26.nextStableObservationCount(
                        3,
                        required,
                        true,
                        false
                )
        );
        assertEquals(
                -1,
                AutoCraftAutomation26.nextStableObservationCount(
                        3,
                        required,
                        false,
                        true
                )
        );
    }
}
