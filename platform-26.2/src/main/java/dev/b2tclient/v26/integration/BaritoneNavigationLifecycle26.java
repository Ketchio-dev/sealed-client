package dev.b2tclient.v26.integration;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic ownership and liveness state machine. It deliberately contains
 * no Minecraft or Baritone types.
 */
final class BaritoneNavigationLifecycle26 {
    enum PathSignal {
        CALC_STARTED,
        PATHING,
        AT_GOAL,
        CALC_FAILED,
        CANCELED
    }

    enum Directive {
        NONE,
        RETRY,
        CANCEL_OWNED
    }

    private static final double MIN_PROGRESS_SQUARED = 0.25D;

    private final BaritoneNavigator26.Limits limits;

    private BaritoneNavigator26.NavigationState state =
            BaritoneNavigator26.NavigationState.IDLE;
    private String detail = "No B2T-owned navigation";
    private BaritoneNavigator26.NavigationTarget target;
    private boolean owned;
    private int retries;
    private long tick;
    private long startedTick;
    private long lastProgressTick;
    private long nextRetryTick;
    private long pausedTick;
    private double bestDistanceSquared = Double.POSITIVE_INFINITY;

    BaritoneNavigationLifecycle26() {
        this(BaritoneNavigator26.Limits.DEFAULT);
    }

    BaritoneNavigationLifecycle26(BaritoneNavigator26.Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    void start(
            BaritoneNavigator26.NavigationTarget requestedTarget,
            double initialDistanceSquared
    ) {
        target = Objects.requireNonNull(requestedTarget, "requestedTarget");
        owned = true;
        retries = 0;
        startedTick = tick;
        lastProgressTick = tick;
        nextRetryTick = 0L;
        pausedTick = 0L;
        bestDistanceSquared = normalizedDistance(initialDistanceSquared);
        state = BaritoneNavigator26.NavigationState.PLANNING;
        detail = "Calculating a path to " + target;
    }

    void pathSignal(PathSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (!owned || state == BaritoneNavigator26.NavigationState.PAUSED) {
            return;
        }
        switch (signal) {
            case CALC_STARTED -> {
                state = BaritoneNavigator26.NavigationState.PLANNING;
                detail = "Calculating path to " + target;
                lastProgressTick = tick;
            }
            case PATHING -> {
                state = BaritoneNavigator26.NavigationState.PATHING;
                detail = "Following path to " + target;
                lastProgressTick = tick;
            }
            case AT_GOAL -> complete();
            case CALC_FAILED ->
                    scheduleRetry("Baritone could not calculate a path");
            case CANCELED -> {
                if (state != BaritoneNavigator26.NavigationState.RETRYING) {
                    cancel("Baritone canceled the B2T-owned path");
                }
            }
        }
    }

    Directive observe(
            double distanceSquared,
            boolean atGoal,
            boolean pathing,
            boolean planning,
            boolean goalMatches
    ) {
        tick++;
        if (!owned) {
            return Directive.NONE;
        }
        if (!goalMatches
                && state != BaritoneNavigator26.NavigationState.PAUSED) {
            releaseOwnership("Baritone goal changed outside B2T");
            return Directive.NONE;
        }
        if (state == BaritoneNavigator26.NavigationState.PAUSED) {
            return Directive.NONE;
        }
        if (atGoal) {
            complete();
            return Directive.NONE;
        }

        double distance = normalizedDistance(distanceSquared);
        if (distance + MIN_PROGRESS_SQUARED < bestDistanceSquared) {
            bestDistanceSquared = distance;
            lastProgressTick = tick;
        }

        if (tick - startedTick >= limits.overallTimeoutTicks()) {
            fail("Navigation timed out after "
                    + formatTicks(tick - startedTick));
            return Directive.CANCEL_OWNED;
        }

        if (state == BaritoneNavigator26.NavigationState.RETRYING) {
            if (tick >= nextRetryTick) {
                if (pathing || planning) {
                    detail = "Waiting for the previous path to stop before retry "
                            + (retries + 1) + "/" + limits.maxRetries();
                    return Directive.NONE;
                }
                retries++;
                state = BaritoneNavigator26.NavigationState.PLANNING;
                lastProgressTick = tick;
                detail = "Retry " + retries + "/" + limits.maxRetries()
                        + " for " + target;
                return Directive.RETRY;
            }
            return Directive.NONE;
        }

        if (pathing) {
            state = BaritoneNavigator26.NavigationState.PATHING;
            detail = progressDetail("Following path");
        } else if (planning) {
            state = BaritoneNavigator26.NavigationState.PLANNING;
            detail = progressDetail("Calculating path");
        }

        if (tick - lastProgressTick >= limits.stallTimeoutTicks()) {
            if (retries < limits.maxRetries()) {
                scheduleRetry(
                        "No path progress for "
                                + formatTicks(tick - lastProgressTick)
                );
                return Directive.CANCEL_OWNED;
            }
            fail("Navigation stalled after " + retries + " retries");
            return Directive.CANCEL_OWNED;
        }
        return Directive.NONE;
    }

    boolean pause() {
        if (!owned
                || state == BaritoneNavigator26.NavigationState.PAUSED
                || state == BaritoneNavigator26.NavigationState.COMPLETED
                || state == BaritoneNavigator26.NavigationState.CANCELLED
                || state == BaritoneNavigator26.NavigationState.FAILED
                || state == BaritoneNavigator26.NavigationState.ERROR) {
            return false;
        }
        state = BaritoneNavigator26.NavigationState.PAUSED;
        pausedTick = tick;
        detail = "Paused B2T navigation to " + target;
        return true;
    }

    boolean resume() {
        if (!owned || state != BaritoneNavigator26.NavigationState.PAUSED) {
            return false;
        }
        long pausedDuration = Math.max(0L, tick - pausedTick);
        startedTick += pausedDuration;
        lastProgressTick += pausedDuration;
        pausedTick = 0L;
        state = BaritoneNavigator26.NavigationState.PLANNING;
        lastProgressTick = tick;
        detail = "Resuming path to " + target;
        return true;
    }

    void cancel(String reason) {
        owned = false;
        state = BaritoneNavigator26.NavigationState.CANCELLED;
        detail = Objects.requireNonNullElse(reason, "Navigation canceled");
    }

    void error(String reason) {
        owned = false;
        state = BaritoneNavigator26.NavigationState.ERROR;
        detail = Objects.requireNonNullElse(
                reason,
                "Baritone integration error"
        );
    }

    void reset() {
        owned = false;
        target = null;
        retries = 0;
        state = BaritoneNavigator26.NavigationState.IDLE;
        detail = "Navigation session reset";
        startedTick = tick;
        lastProgressTick = tick;
        nextRetryTick = 0L;
        pausedTick = 0L;
        bestDistanceSquared = Double.POSITIVE_INFINITY;
    }

    boolean owned() {
        return owned;
    }

    boolean paused() {
        return state == BaritoneNavigator26.NavigationState.PAUSED;
    }

    BaritoneNavigator26.NavigationState state() {
        return state;
    }

    BaritoneNavigator26.NavigationStatus snapshot() {
        long effectiveTick =
                state == BaritoneNavigator26.NavigationState.PAUSED
                        ? pausedTick
                        : tick;
        long elapsed = target == null
                ? 0L
                : Math.max(0L, effectiveTick - startedTick);
        return new BaritoneNavigator26.NavigationStatus(
                state,
                detail,
                owned,
                target,
                retries,
                elapsed
        );
    }

    private void scheduleRetry(String reason) {
        if (retries >= limits.maxRetries()) {
            fail(reason + "; retry limit reached");
            return;
        }
        state = BaritoneNavigator26.NavigationState.RETRYING;
        nextRetryTick = tick + limits.retryDelayTicks();
        lastProgressTick = tick;
        detail = reason + "; retry " + (retries + 1) + "/"
                + limits.maxRetries() + " in "
                + formatTicks(limits.retryDelayTicks());
    }

    private void complete() {
        owned = false;
        state = BaritoneNavigator26.NavigationState.COMPLETED;
        detail = "Arrived at " + target + " in "
                + formatTicks(tick - startedTick)
                + (retries == 0 ? "" : " after " + retries + " retries");
    }

    private void fail(String reason) {
        owned = false;
        state = BaritoneNavigator26.NavigationState.FAILED;
        detail = reason;
    }

    private void releaseOwnership(String reason) {
        owned = false;
        state = BaritoneNavigator26.NavigationState.IDLE;
        detail = reason;
    }

    private String progressDetail(String action) {
        if (!Double.isFinite(bestDistanceSquared)) {
            return action + " to " + target;
        }
        return String.format(
                Locale.ROOT,
                "%s to %s \u2022 %.1f blocks remaining",
                action,
                target,
                Math.sqrt(bestDistanceSquared)
        );
    }

    private static double normalizedDistance(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0D
                ? distanceSquared
                : Double.POSITIVE_INFINITY;
    }

    private static String formatTicks(long ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0D);
    }
}
