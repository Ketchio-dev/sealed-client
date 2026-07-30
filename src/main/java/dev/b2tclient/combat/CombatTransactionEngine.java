package dev.b2tclient.combat;

import java.util.Objects;

/**
 * A small tick-driven state machine for combat actions which require an
 * observable server acknowledgement. The engine never schedules work itself:
 * callers receive a single {@link Directive#RETRY} when a bounded retry is due.
 *
 * @param <K> the immutable key used to correlate a confirmation packet
 */
public final class CombatTransactionEngine<K> {
    private final int confirmationTimeoutTicks;
    private final int maximumRetries;
    private final int baseBackoffTicks;

    private Phase phase = Phase.IDLE;
    private Action action;
    private K key;
    private int attempts;
    private long deadlineTick;
    private long transitionTick;
    private String detail = "idle";

    public CombatTransactionEngine(
            int confirmationTimeoutTicks,
            int maximumRetries,
            int baseBackoffTicks
    ) {
        if (confirmationTimeoutTicks < 1) {
            throw new IllegalArgumentException("confirmationTimeoutTicks must be positive");
        }
        if (maximumRetries < 0) {
            throw new IllegalArgumentException("maximumRetries cannot be negative");
        }
        if (baseBackoffTicks < 1) {
            throw new IllegalArgumentException("baseBackoffTicks must be positive");
        }
        this.confirmationTimeoutTicks = confirmationTimeoutTicks;
        this.maximumRetries = maximumRetries;
        this.baseBackoffTicks = baseBackoffTicks;
    }

    public synchronized boolean begin(Action requestedAction, K requestedKey, long nowTick) {
        Objects.requireNonNull(requestedAction, "requestedAction");
        Objects.requireNonNull(requestedKey, "requestedKey");
        if (phase != Phase.IDLE) {
            return false;
        }
        action = requestedAction;
        key = requestedKey;
        attempts = 1;
        phase = Phase.AWAITING_CONFIRMATION;
        transitionTick = nowTick;
        deadlineTick = saturatingAdd(nowTick, confirmationTimeoutTicks);
        detail = "sent";
        return true;
    }

    /**
     * Advances the state machine by a logical client tick.
     *
     * <p>At most one retry directive is emitted for an attempt. The phase moves
     * back to awaiting confirmation before the directive is returned, so
     * repeated calls for the same tick cannot generate duplicate sends.</p>
     */
    public synchronized Directive advance(long nowTick) {
        if (phase == Phase.AWAITING_CONFIRMATION && nowTick >= deadlineTick) {
            int retriesUsed = attempts - 1;
            if (retriesUsed >= maximumRetries) {
                phase = Phase.FAILED;
                transitionTick = nowTick;
                detail = "confirmation_timeout";
                return Directive.FAILED;
            }
            phase = Phase.RETRY_BACKOFF;
            transitionTick = nowTick;
            deadlineTick = saturatingAdd(
                    nowTick,
                    (long) baseBackoffTicks << Math.min(retriesUsed, 20)
            );
            detail = "retry_backoff";
            return Directive.NONE;
        }
        if (phase == Phase.RETRY_BACKOFF && nowTick >= deadlineTick) {
            attempts++;
            phase = Phase.AWAITING_CONFIRMATION;
            transitionTick = nowTick;
            deadlineTick = saturatingAdd(nowTick, confirmationTimeoutTicks);
            detail = "retry_sent";
            return Directive.RETRY;
        }
        return Directive.NONE;
    }

    public synchronized Confirmation confirm(
            Action confirmedAction,
            K confirmedKey,
            long nowTick
    ) {
        Objects.requireNonNull(confirmedAction, "confirmedAction");
        Objects.requireNonNull(confirmedKey, "confirmedKey");
        if (phase == Phase.CONFIRMED
                && confirmedAction == action
                && confirmedKey.equals(key)) {
            return Confirmation.DUPLICATE;
        }
        if ((phase != Phase.AWAITING_CONFIRMATION
                && phase != Phase.RETRY_BACKOFF)
                || confirmedAction != action
                || !confirmedKey.equals(key)) {
            return Confirmation.IGNORED;
        }
        phase = Phase.CONFIRMED;
        transitionTick = nowTick;
        deadlineTick = nowTick;
        detail = "confirmed";
        return Confirmation.ACCEPTED;
    }

    public synchronized boolean fail(String reason, long nowTick) {
        Objects.requireNonNull(reason, "reason");
        if (phase != Phase.AWAITING_CONFIRMATION
                && phase != Phase.RETRY_BACKOFF) {
            return false;
        }
        phase = Phase.FAILED;
        transitionTick = nowTick;
        deadlineTick = nowTick;
        detail = reason.isBlank() ? "failed" : reason;
        return true;
    }

    public synchronized void reset(String reason, long nowTick) {
        phase = Phase.IDLE;
        action = null;
        key = null;
        attempts = 0;
        deadlineTick = nowTick;
        transitionTick = nowTick;
        detail = reason == null || reason.isBlank() ? "idle" : reason;
    }

    public synchronized Snapshot<K> snapshot() {
        return new Snapshot<>(
                phase,
                action,
                key,
                attempts,
                maximumRetries,
                deadlineTick,
                transitionTick,
                detail
        );
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public enum Action {
        PLACE,
        BREAK
    }

    public enum Phase {
        IDLE,
        AWAITING_CONFIRMATION,
        RETRY_BACKOFF,
        CONFIRMED,
        FAILED
    }

    public enum Directive {
        NONE,
        RETRY,
        FAILED
    }

    public enum Confirmation {
        ACCEPTED,
        DUPLICATE,
        IGNORED
    }

    public record Snapshot<K>(
            Phase phase,
            Action action,
            K key,
            int attempts,
            int maximumRetries,
            long deadlineTick,
            long transitionTick,
            String detail
    ) {
        public Snapshot {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(detail, "detail");
        }

        public boolean terminal() {
            return phase == Phase.CONFIRMED || phase == Phase.FAILED;
        }

        public String concise() {
            String actionName = action == null ? "none" : action.name().toLowerCase();
            return phase.name().toLowerCase()
                    + ':' + actionName
                    + ":attempt=" + attempts
                    + '/' + (maximumRetries + 1)
                    + ':' + detail;
        }
    }
}
