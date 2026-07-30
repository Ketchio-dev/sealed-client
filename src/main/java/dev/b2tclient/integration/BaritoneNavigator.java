package dev.b2tclient.integration;

import java.util.Objects;

/**
 * Version-neutral, optional navigation boundary. No Baritone type is exposed
 * here, so callers can load and use the client when Baritone is absent.
 */
public interface BaritoneNavigator {
    boolean available();

    String version();

    NavigationResult goTo(int x, int y, int z);

    default NavigationResult pause() {
        return NavigationResult.failure("This Baritone integration cannot pause navigation");
    }

    default NavigationResult resume() {
        return NavigationResult.failure("This Baritone integration cannot resume navigation");
    }

    NavigationResult stop();

    NavigationStatus status();

    /**
     * Cancels only navigation that was started through this B2T boundary.
     */
    void releaseOwnedNavigation();

    /**
     * Clears transient lifecycle state when the client disconnects or changes
     * worlds. Implementations must not disturb navigation they do not own.
     */
    default void resetSession() {
        releaseOwnedNavigation();
    }

    static BaritoneNavigator unavailable(String version, String detail) {
        return new UnavailableBaritoneNavigator(version, detail);
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
            boolean ownedByB2T,
            NavigationTarget target,
            int retryCount,
            long elapsedTicks
    ) {
        public NavigationStatus(
                NavigationState state,
                String detail,
                boolean ownedByB2T
        ) {
            this(state, detail, ownedByB2T, null, 0, 0L);
        }

        public NavigationStatus {
            state = Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
            if (retryCount < 0) {
                throw new IllegalArgumentException("retryCount must not be negative");
            }
            if (elapsedTicks < 0L) {
                throw new IllegalArgumentException("elapsedTicks must not be negative");
            }
        }
    }

    record NavigationTarget(int x, int y, int z) {
        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }
}
