package dev.sealedclient.v26.movement;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Small monotonic-tick action budget used for movement packets.
 *
 * <p>It prevents repeated per-tick packet emission even when a caller's
 * per-fall latch is reset by noisy client observations. Tick regression is
 * treated as a new session and clears all old permits.</p>
 */
public final class MovementPacketBudget26 {
    private final int maximumActions;
    private final int windowTicks;
    private final int minimumSpacingTicks;
    private final Deque<Long> actions = new ArrayDeque<>();
    private long latestTick = Long.MIN_VALUE;

    public MovementPacketBudget26(
            int maximumActions,
            int windowTicks,
            int minimumSpacingTicks
    ) {
        if (maximumActions < 1 || maximumActions > 16) {
            throw new IllegalArgumentException(
                    "Maximum packet actions must be in [1, 16]"
            );
        }
        if (windowTicks < 1 || windowTicks > 20_000) {
            throw new IllegalArgumentException(
                    "Packet window must be in [1, 20000]"
            );
        }
        if (minimumSpacingTicks < 0
                || minimumSpacingTicks > windowTicks) {
            throw new IllegalArgumentException(
                    "Packet spacing must be in [0, windowTicks]"
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
        long nextSpacingTick = previous == null
                ? tick
                : previous + minimumSpacingTicks;
        Long oldest = actions.peekFirst();
        long nextWindowTick = oldest == null
                ? tick
                : oldest + windowTicks;
        return new Snapshot(
                actions.size(),
                maximumActions,
                Math.max(tick, nextSpacingTick),
                actions.size() >= maximumActions
                        ? Math.max(
                                tick,
                                Math.max(nextWindowTick, nextSpacingTick)
                        )
                        : Math.max(tick, nextSpacingTick)
        );
    }

    private void observeTick(long tick) {
        if (tick < 0L) {
            throw new IllegalArgumentException(
                    "Movement packet tick cannot be negative"
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
            long nextSpacingTick,
            long nextAvailableTick
    ) {
    }
}
