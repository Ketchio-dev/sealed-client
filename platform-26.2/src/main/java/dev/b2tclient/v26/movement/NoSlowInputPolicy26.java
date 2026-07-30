package dev.b2tclient.v26.movement;

/**
 * Fail-closed policy for the one vanilla branch that scales movement input
 * while a local player is using an item.
 */
public final class NoSlowInputPolicy26 {
    public boolean shouldBypass(Observation observation) {
        return observation != null
                && observation.enabled()
                && observation.sessionActive()
                && observation.playerPresent()
                && observation.playerAlive()
                && observation.usingItem()
                && !observation.passenger();
    }

    public record Observation(
            boolean enabled,
            boolean sessionActive,
            boolean playerPresent,
            boolean playerAlive,
            boolean usingItem,
            boolean passenger
    ) {
    }
}
