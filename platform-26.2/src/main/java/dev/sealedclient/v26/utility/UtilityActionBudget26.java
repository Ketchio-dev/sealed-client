package dev.sealedclient.v26.utility;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Monotonic-tick rolling action budget for packet-producing utility work.
 */
public final class UtilityActionBudget26 {
    private final int maximumActions;
    private final int windowTicks;
    private final int minimumSpacingTicks;
    private final Deque<Long> actions = new ArrayDeque<>();
    private long latestTick = Long.MIN_VALUE;

    public UtilityActionBudget26(
            int maximumActions,
            int windowTicks,
            int minimumSpacingTicks
    ) {
        if (maximumActions < 1 || maximumActions > 128) {
            throw new IllegalArgumentException(
                    "Maximum utility actions must be in [1, 128]"
            );
        }
        if (windowTicks < 1 || windowTicks > 20_000) {
            throw new IllegalArgumentException(
                    "Utility action window must be in [1, 20000]"
            );
        }
        if (minimumSpacingTicks < 0
                || minimumSpacingTicks > windowTicks) {
            throw new IllegalArgumentException(
                    "Utility action spacing must be in [0, windowTicks]"
            );
        }
        this.maximumActions = maximumActions;
        this.windowTicks = windowTicks;
        this.minimumSpacingTicks = minimumSpacingTicks;
    }

    public boolean canAcquire(long tick) {
        observeTick(tick);
        evictExpired(tick);
        if (actions.size() >= maximumActions) {
            return false;
        }
        Long previous = actions.peekLast();
        return previous == null || tick - previous >= minimumSpacingTicks;
    }

    public boolean acquire(long tick) {
        if (!canAcquire(tick)) {
            return false;
        }
        actions.addLast(tick);
        return true;
    }

    public void reset() {
        actions.clear();
        latestTick = Long.MIN_VALUE;
    }

    public Snapshot snapshot(long tick) {
        observeTick(tick);
        evictExpired(tick);
        Long previous = actions.peekLast();
        long spacing = previous == null
                ? tick
                : previous + minimumSpacingTicks;
        Long oldest = actions.peekFirst();
        long capacity = oldest == null
                ? tick
                : oldest + windowTicks;
        long next = actions.size() >= maximumActions
                ? Math.max(spacing, capacity)
                : spacing;
        return new Snapshot(
                actions.size(),
                maximumActions,
                Math.max(tick, next)
        );
    }

    private void observeTick(long tick) {
        if (tick < 0L) {
            throw new IllegalArgumentException(
                    "Utility action tick cannot be negative"
            );
        }
        if (latestTick != Long.MIN_VALUE && tick < latestTick) {
            actions.clear();
        }
        latestTick = tick;
    }

    private void evictExpired(long tick) {
        while (!actions.isEmpty()
                && tick - actions.peekFirst() >= windowTicks) {
            actions.removeFirst();
        }
    }

    public record Snapshot(
            int actionsInWindow,
            int maximumActions,
            long nextAvailableTick
    ) {
    }
}
