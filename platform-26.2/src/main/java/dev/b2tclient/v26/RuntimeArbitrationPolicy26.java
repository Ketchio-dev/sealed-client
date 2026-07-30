package dev.b2tclient.v26;

import dev.b2tclient.v26.integration.BaritoneNavigator26;

/**
 * Pure central arbitration rules shared by the live 26.2 runtime.
 *
 * <p>Keeping these decisions independent of Minecraft state makes the
 * screen/transport and movement-sensitive safety boundaries deterministic
 * and regression-testable.</p>
 */
final class RuntimeArbitrationPolicy26 {
    private RuntimeArbitrationPolicy26() {
    }

    static UtilityReadiness utilityReadiness(
            boolean sessionActive,
            boolean movementNetworkReady,
            boolean freecamOwnsMovement,
            boolean baritoneOwnsMovement
    ) {
        boolean transportReady = sessionActive
                && !freecamOwnsMovement
                && !baritoneOwnsMovement;
        return new UtilityReadiness(
                transportReady,
                transportReady && movementNetworkReady
        );
    }

    static boolean baritoneBlocked(
            boolean combatActionGranted,
            boolean freecamRequested,
            boolean screenOpen
    ) {
        return combatActionGranted || freecamRequested || screenOpen;
    }

    static boolean baritoneModuleShouldDeactivate(
            boolean ownedByB2T,
            boolean targetPending,
            BaritoneNavigator26.NavigationState state
    ) {
        if (ownedByB2T || targetPending) {
            return false;
        }
        return switch (state) {
            case IDLE, COMPLETED, CANCELLED, FAILED, ERROR, UNAVAILABLE -> true;
            case PLANNING, PATHING, PAUSED, RETRYING -> false;
        };
    }

    record UtilityReadiness(
            boolean transportReady,
            boolean movementSensitiveReady
    ) {
    }
}
