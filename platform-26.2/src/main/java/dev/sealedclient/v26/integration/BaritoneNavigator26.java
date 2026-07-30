package dev.sealedclient.v26.integration;

import java.util.Objects;

/**
 * Version-neutral boundary for an optionally installed Baritone provider.
 *
 * <p>No Baritone type is exposed by this API. The platform can therefore load
 * and run when Baritone is absent or when an installed provider is rejected as
 * incompatible.</p>
 */
public interface BaritoneNavigator26 {
    boolean available();

    String version();

    NavigationResult goTo(int x, int y, int z);

    default NavigationResult pause() {
        return NavigationResult.failure(
                "This Baritone integration cannot pause navigation"
        );
    }

    default NavigationResult resume() {
        return NavigationResult.failure(
                "This Baritone integration cannot resume navigation"
        );
    }

    NavigationResult stop();

    NavigationStatus status();

    /**
     * Returns whether callers must keep other movement automation from
     * driving the player. This includes a Sealed path whose cancellation was
     * accepted but cannot finish until Baritone reaches an interruptible
     * segment.
     *
     * <p>This is a local snapshot and must not contact Baritone.</p>
     */
    default boolean movementReserved() {
        NavigationStatus current = status();
        if (!current.ownedBySealed()) {
            return false;
        }
        return switch (current.state()) {
            case PLANNING, PATHING, RETRYING -> true;
            default -> false;
        };
    }

    /**
     * Samples provider state once. Call this at most once per client tick while
     * a play session is active. This method never emits chat commands.
     */
    default void tick() {
    }

    /**
     * Releases only a goal started through this navigator.
     */
    void releaseOwnedNavigation();

    /**
     * Clears transient state on disconnect, respawn, or dimension transition.
     * Implementations must not cancel a goal whose identity is not the exact
     * object originally submitted by Sealed Client.
     */
    default void resetSession() {
        releaseOwnedNavigation();
    }

    enum NavigationState {
        UNAVAILABLE,
        IDLE,
        PLANNING,
        PATHING,
        PAUSED,
        RETRYING,
        COMPLETED,
        CANCELLED,
        FAILED,
        ERROR
    }

    record NavigationResult(boolean success, String message) {
        public NavigationResult {
            message = Objects.requireNonNullElse(message, "");
        }

        public static NavigationResult success(String message) {
            return new NavigationResult(true, message);
        }

        public static NavigationResult failure(String message) {
            return new NavigationResult(false, message);
        }
    }

    record NavigationStatus(
            NavigationState state,
            String detail,
            boolean ownedBySealed,
            NavigationTarget target,
            int retryCount,
            long elapsedTicks
    ) {
        public NavigationStatus(
                NavigationState state,
                String detail,
                boolean ownedBySealed
        ) {
            this(state, detail, ownedBySealed, null, 0, 0L);
        }

        public NavigationStatus {
            state = Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            if (retryCount < 0) {
                throw new IllegalArgumentException(
                        "retryCount must not be negative"
                );
            }
            if (elapsedTicks < 0L) {
                throw new IllegalArgumentException(
                        "elapsedTicks must not be negative"
                );
            }
        }
    }

    record NavigationTarget(int x, int y, int z) {
        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }

    record Limits(
            int maxRetries,
            long retryDelayTicks,
            long stallTimeoutTicks,
            long overallTimeoutTicks
    ) {
        public static final Limits DEFAULT =
                new Limits(2, 20L, 30L * 20L, 10L * 60L * 20L);

        public Limits {
            if (maxRetries < 0
                    || retryDelayTicks < 0L
                    || stallTimeoutTicks < 1L
                    || overallTimeoutTicks < stallTimeoutTicks) {
                throw new IllegalArgumentException(
                        "Invalid Baritone lifecycle limits"
                );
            }
        }
    }
}
