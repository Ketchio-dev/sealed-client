package dev.sealedclient.v26.combat;

/**
 * Pure confirmation/retry state machine. A retry remains ready until the live
 * adapter actually wins arbitration and sends it, so losing one tick cannot
 * silently consume the retry budget.
 */
final class ConfirmationState26 {
    private final int timeoutTicks;
    private final int maximumRetries;
    private Phase phase = Phase.IDLE;
    private Action action;
    private long key = -1L;
    private long deadline;
    private int retries;

    ConfirmationState26(int timeoutTicks, int maximumRetries) {
        if (timeoutTicks <= 0 || maximumRetries < 0) {
            throw new IllegalArgumentException("Invalid confirmation limits");
        }
        this.timeoutTicks = timeoutTicks;
        this.maximumRetries = maximumRetries;
    }

    boolean begin(Action requestedAction, long requestedKey, long tick) {
        if (phase != Phase.IDLE
                || requestedAction == null
                || requestedKey < 0L
                || tick < 0L) {
            return false;
        }
        action = requestedAction;
        key = requestedKey;
        retries = 0;
        deadline = saturatingAdd(tick, timeoutTicks);
        phase = Phase.AWAITING_CONFIRMATION;
        return true;
    }

    Directive advance(long tick) {
        if (tick < 0L) {
            return Directive.NONE;
        }
        if (phase == Phase.RETRY_READY) {
            return Directive.RETRY;
        }
        if (phase != Phase.AWAITING_CONFIRMATION || tick < deadline) {
            return Directive.NONE;
        }
        if (retries >= maximumRetries) {
            phase = Phase.FAILED;
            return Directive.FAILED;
        }
        phase = Phase.RETRY_READY;
        return Directive.RETRY;
    }

    boolean markRetried(long tick) {
        if (phase != Phase.RETRY_READY || tick < 0L) {
            return false;
        }
        retries++;
        deadline = saturatingAdd(tick, timeoutTicks);
        phase = Phase.AWAITING_CONFIRMATION;
        return true;
    }

    boolean confirm(Action observedAction, long observedKey) {
        if ((phase != Phase.AWAITING_CONFIRMATION
                && phase != Phase.RETRY_READY)
                || observedAction != action
                || observedKey != key) {
            return false;
        }
        phase = Phase.CONFIRMED;
        return true;
    }

    void fail() {
        if (phase != Phase.IDLE) {
            phase = Phase.FAILED;
        }
    }

    void reset() {
        phase = Phase.IDLE;
        action = null;
        key = -1L;
        deadline = 0L;
        retries = 0;
    }

    Snapshot snapshot() {
        return new Snapshot(phase, action, key, deadline, retries);
    }

    private static long saturatingAdd(long value, int amount) {
        return value > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE
                : value + amount;
    }

    enum Action {
        BREAK,
        PLACE
    }

    enum Phase {
        IDLE,
        AWAITING_CONFIRMATION,
        RETRY_READY,
        CONFIRMED,
        FAILED
    }

    enum Directive {
        NONE,
        RETRY,
        FAILED
    }

    record Snapshot(
            Phase phase,
            Action action,
            long key,
            long deadline,
            int retries
    ) {
    }
}
