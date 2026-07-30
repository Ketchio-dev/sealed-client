package dev.sealedclient.integration;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic ownership and liveness state machine used by the optional
 * Baritone adapter. It deliberately contains no Baritone or Minecraft types.
 */
final class BaritoneNavigationLifecycle {
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

    private final int maxRetries;
    private final long retryDelayTicks;
    private final long stallTimeoutTicks;
    private final long overallTimeoutTicks;

    private BaritoneNavigator.NavigationState state =
            BaritoneNavigator.NavigationState.IDLE;
    private String detail = "No Sealed-owned navigation";
    private BaritoneNavigator.NavigationTarget target;
    private boolean owned;
    private int retries;
    private long tick;
    private long startedTick;
    private long lastProgressTick;
    private long nextRetryTick;
    private long pausedTick;
    private double bestDistanceSquared = Double.POSITIVE_INFINITY;

    BaritoneNavigationLifecycle() {
        this(2, 20L, 30L * 20L, 10L * 60L * 20L);
    }

    BaritoneNavigationLifecycle(
            int maxRetries,
            long retryDelayTicks,
            long stallTimeoutTicks,
            long overallTimeoutTicks
    ) {
        if (maxRetries < 0
                || retryDelayTicks < 0L
                || stallTimeoutTicks < 1L
                || overallTimeoutTicks < stallTimeoutTicks) {
            throw new IllegalArgumentException("Invalid Baritone lifecycle limits");
        }
        this.maxRetries = maxRetries;
        this.retryDelayTicks = retryDelayTicks;
        this.stallTimeoutTicks = stallTimeoutTicks;
        this.overallTimeoutTicks = overallTimeoutTicks;
    }

    void start(
            BaritoneNavigator.NavigationTarget requestedTarget,
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
        state = BaritoneNavigator.NavigationState.PLANNING;
        detail = "Calculating a path to " + target;
    }

    void pathSignal(PathSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (!owned) {
            return;
        }
        if (state == BaritoneNavigator.NavigationState.PAUSED) {
            return;
        }
        switch (signal) {
            case CALC_STARTED -> {
                state = BaritoneNavigator.NavigationState.PLANNING;
                detail = "Calculating path to " + target;
                lastProgressTick = tick;
            }
            case PATHING -> {
                state = BaritoneNavigator.NavigationState.PATHING;
                detail = "Following path to " + target;
                lastProgressTick = tick;
            }
            case AT_GOAL -> complete();
            case CALC_FAILED -> scheduleRetry("Baritone could not calculate a path");
            case CANCELED -> {
                if (state != BaritoneNavigator.NavigationState.PAUSED
                        && state != BaritoneNavigator.NavigationState.RETRYING) {
                    cancel("Baritone canceled the Sealed-owned path");
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
        if (!goalMatches && state != BaritoneNavigator.NavigationState.PAUSED) {
            releaseOwnership("Baritone goal changed outside Sealed Client");
            return Directive.NONE;
        }
        if (state == BaritoneNavigator.NavigationState.PAUSED) {
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

        if (tick - startedTick >= overallTimeoutTicks) {
            fail("Navigation timed out after " + formatTicks(tick - startedTick));
            return Directive.CANCEL_OWNED;
        }

        if (state == BaritoneNavigator.NavigationState.RETRYING) {
            if (tick >= nextRetryTick) {
                retries++;
                state = BaritoneNavigator.NavigationState.PLANNING;
                lastProgressTick = tick;
                detail = "Retry " + retries + "/" + maxRetries + " for " + target;
                return Directive.RETRY;
            }
            return Directive.NONE;
        }

        if (pathing) {
            state = BaritoneNavigator.NavigationState.PATHING;
            detail = progressDetail("Following path");
        } else if (planning) {
            state = BaritoneNavigator.NavigationState.PLANNING;
            detail = progressDetail("Calculating path");
        }

        if (tick - lastProgressTick >= stallTimeoutTicks) {
            if (retries < maxRetries) {
                scheduleRetry("No path progress for "
                        + formatTicks(tick - lastProgressTick));
                return Directive.CANCEL_OWNED;
            }
            fail("Navigation stalled after " + retries + " retries");
            return Directive.CANCEL_OWNED;
        }
        return Directive.NONE;
    }

    boolean pause() {
        if (!owned
                || state == BaritoneNavigator.NavigationState.PAUSED
                || state == BaritoneNavigator.NavigationState.COMPLETED
                || state == BaritoneNavigator.NavigationState.CANCELLED
                || state == BaritoneNavigator.NavigationState.FAILED) {
            return false;
        }
        state = BaritoneNavigator.NavigationState.PAUSED;
        pausedTick = tick;
        detail = "Paused Sealed navigation to " + target;
        return true;
    }

    boolean resume() {
        if (!owned || state != BaritoneNavigator.NavigationState.PAUSED) {
            return false;
        }
        long pausedDuration = Math.max(0L, tick - pausedTick);
        startedTick += pausedDuration;
        lastProgressTick += pausedDuration;
        pausedTick = 0L;
        state = BaritoneNavigator.NavigationState.PLANNING;
        lastProgressTick = tick;
        detail = "Resuming path to " + target;
        return true;
    }

    void cancel(String reason) {
        owned = false;
        state = BaritoneNavigator.NavigationState.CANCELLED;
        detail = Objects.requireNonNullElse(reason, "Navigation canceled");
    }

    void error(String reason) {
        owned = false;
        state = BaritoneNavigator.NavigationState.ERROR;
        detail = Objects.requireNonNullElse(reason, "Baritone integration error");
    }

    void reset() {
        owned = false;
        target = null;
        retries = 0;
        state = BaritoneNavigator.NavigationState.IDLE;
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
        return state == BaritoneNavigator.NavigationState.PAUSED;
    }

    BaritoneNavigator.NavigationTarget target() {
        return target;
    }

    BaritoneNavigator.NavigationStatus snapshot() {
        long effectiveTick = state == BaritoneNavigator.NavigationState.PAUSED
                ? pausedTick
                : tick;
        long elapsed = target == null
                ? 0L
                : Math.max(0L, effectiveTick - startedTick);
        return new BaritoneNavigator.NavigationStatus(
                state,
                detail,
                owned,
                target,
                retries,
                elapsed
        );
    }

    private void scheduleRetry(String reason) {
        if (retries >= maxRetries) {
            fail(reason + "; retry limit reached");
            return;
        }
        state = BaritoneNavigator.NavigationState.RETRYING;
        nextRetryTick = tick + retryDelayTicks;
        lastProgressTick = tick;
        detail = reason + "; retry " + (retries + 1) + "/" + maxRetries
                + " in " + formatTicks(retryDelayTicks);
    }

    private void complete() {
        owned = false;
        state = BaritoneNavigator.NavigationState.COMPLETED;
        detail = "Arrived at " + target + " in " + formatTicks(tick - startedTick)
                + (retries == 0 ? "" : " after " + retries + " retries");
    }

    private void fail(String reason) {
        owned = false;
        state = BaritoneNavigator.NavigationState.FAILED;
        detail = reason;
    }

    private void releaseOwnership(String reason) {
        owned = false;
        state = BaritoneNavigator.NavigationState.IDLE;
        detail = reason;
    }

    private String progressDetail(String action) {
        if (!Double.isFinite(bestDistanceSquared)) {
            return action + " to " + target;
        }
        return String.format(
                Locale.ROOT,
                "%s to %s • %.1f blocks remaining",
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
