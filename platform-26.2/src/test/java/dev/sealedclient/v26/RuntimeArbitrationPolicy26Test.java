package dev.sealedclient.v26;

import dev.sealedclient.v26.integration.BaritoneNavigator26;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeArbitrationPolicy26Test {
    @Test
    void craftingTransportCanRemainReadyWhenMovementSafetyIsPaused() {
        var readiness = RuntimeArbitrationPolicy26.utilityReadiness(
                true,
                false,
                false,
                false
        );

        assertTrue(readiness.transportReady());
        assertFalse(readiness.movementSensitiveReady());
    }

    @Test
    void freecamAndBaritoneReserveAllUtilityTransport() {
        var freecam = RuntimeArbitrationPolicy26.utilityReadiness(
                true,
                true,
                true,
                false
        );
        var baritone = RuntimeArbitrationPolicy26.utilityReadiness(
                true,
                true,
                false,
                true
        );

        assertFalse(freecam.transportReady());
        assertFalse(freecam.movementSensitiveReady());
        assertFalse(baritone.transportReady());
        assertFalse(baritone.movementSensitiveReady());
    }

    @Test
    void baritonePausesForAnyCombatGrantFreecamOrOpenScreen() {
        assertTrue(RuntimeArbitrationPolicy26.baritoneBlocked(
                true,
                false,
                false
        ));
        assertTrue(RuntimeArbitrationPolicy26.baritoneBlocked(
                false,
                true,
                false
        ));
        assertTrue(RuntimeArbitrationPolicy26.baritoneBlocked(
                false,
                false,
                true
        ));
        assertFalse(RuntimeArbitrationPolicy26.baritoneBlocked(
                false,
                false,
                false
        ));
    }

    @Test
    void baritoneModuleTurnsOffAfterTerminalOrIdleStateOnly() {
        assertTrue(RuntimeArbitrationPolicy26.baritoneModuleShouldDeactivate(
                false,
                false,
                BaritoneNavigator26.NavigationState.COMPLETED
        ));
        assertTrue(RuntimeArbitrationPolicy26.baritoneModuleShouldDeactivate(
                false,
                false,
                BaritoneNavigator26.NavigationState.IDLE
        ));
        assertFalse(RuntimeArbitrationPolicy26.baritoneModuleShouldDeactivate(
                true,
                false,
                BaritoneNavigator26.NavigationState.PATHING
        ));
        assertFalse(RuntimeArbitrationPolicy26.baritoneModuleShouldDeactivate(
                false,
                true,
                BaritoneNavigator26.NavigationState.IDLE
        ));
    }
}
