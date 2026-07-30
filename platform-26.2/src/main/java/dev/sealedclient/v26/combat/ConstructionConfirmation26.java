package dev.sealedclient.v26.combat;

/**
 * Pure server-reflection confirmation with bounded retries.
 *
 * <p>A retry remains ready until the live adapter wins every required arbiter
 * channel and actually sends the interaction. Merely losing arbitration never
 * consumes retry budget.</p>
 */
final class ConstructionConfirmation26 {
    private final int timeoutTicks;
    private final int maximumRetries;
    private Phase phase = Phase.IDLE;
    private long key = -1L;
    private long deadline;
    private int retries;

    ConstructionConfirmation26(int timeoutTicks, int maximumRetries) {
        if (timeoutTicks <= 0 || maximumRetries < 0) {
            throw new IllegalArgumentException(
                    "Invalid construction confirmation limits"
            );
        }
        this.timeoutTicks = timeoutTicks;
        this.maximumRetries = maximumRetries;
    }

    boolean begin(long requestedKey, long tick) {
        if (phase != Phase.IDLE || requestedKey < 0L || tick < 0L) {
            return false;
        }
        key = requestedKey;
        deadline = saturatingAdd(tick, timeoutTicks);
        retries = 0;
        phase = Phase.AWAITING_CONFIRMATION;
        return true;
    }

    Result observe(long observedKey, boolean worldConfirmed, long tick) {
        if ((phase != Phase.AWAITING_CONFIRMATION
                && phase != Phase.RETRY_READY)
                || observedKey != key
                || tick < 0L) {
            return Result.NONE;
        }
        if (worldConfirmed) {
            phase = Phase.CONFIRMED;
            return Result.CONFIRMED;
        }
        if (phase == Phase.RETRY_READY) {
            return Result.RETRY;
        }
        if (tick < deadline) {
            return Result.WAIT;
        }
        if (retries >= maximumRetries) {
            phase = Phase.FAILED;
            return Result.FAILED;
        }
        phase = Phase.RETRY_READY;
        return Result.RETRY;
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

    void fail() {
        if (phase != Phase.IDLE) {
            phase = Phase.FAILED;
        }
    }

    void reset() {
        phase = Phase.IDLE;
        key = -1L;
        deadline = 0L;
        retries = 0;
    }

    Snapshot snapshot() {
        return new Snapshot(phase, key, deadline, retries);
    }

    private static long saturatingAdd(long value, int amount) {
        return value > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE
                : value + amount;
    }

    enum Phase {
        IDLE,
        AWAITING_CONFIRMATION,
        RETRY_READY,
        CONFIRMED,
        FAILED
    }

    enum Result {
        NONE,
        WAIT,
        RETRY,
        CONFIRMED,
        FAILED
    }

    record Snapshot(
            Phase phase,
            long key,
            long deadline,
            int retries
    ) {
    }
}
