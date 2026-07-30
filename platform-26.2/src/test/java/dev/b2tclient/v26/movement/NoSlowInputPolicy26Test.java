package dev.b2tclient.v26.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoSlowInputPolicy26Test {
    private final NoSlowInputPolicy26 policy = new NoSlowInputPolicy26();

    @Test
    void bypassesOnlyActiveLocalItemUse() {
        assertTrue(policy.shouldBypass(observation(
                true, true, true, true, true, false
        )));
        assertFalse(policy.shouldBypass(observation(
                false, true, true, true, true, false
        )));
        assertFalse(policy.shouldBypass(observation(
                true, false, true, true, true, false
        )));
        assertFalse(policy.shouldBypass(observation(
                true, true, true, true, false, false
        )));
    }

    @Test
    void passengersAndDeadPlayersKeepVanillaBehavior() {
        assertFalse(policy.shouldBypass(observation(
                true, true, true, true, true, true
        )));
        assertFalse(policy.shouldBypass(observation(
                true, true, true, false, true, false
        )));
        assertFalse(policy.shouldBypass(null));
    }

    private static NoSlowInputPolicy26.Observation observation(
            boolean enabled,
            boolean sessionActive,
            boolean playerPresent,
            boolean playerAlive,
            boolean usingItem,
            boolean passenger
    ) {
        return new NoSlowInputPolicy26.Observation(
                enabled,
                sessionActive,
                playerPresent,
                playerAlive,
                usingItem,
                passenger
        );
    }
}
