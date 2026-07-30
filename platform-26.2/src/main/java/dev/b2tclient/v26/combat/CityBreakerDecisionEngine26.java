package dev.b2tclient.v26.combat;

import java.util.List;

/**
 * Pure and deterministic City Breaker planning primitives.
 *
 * <p>The live adapter supplies already sampled target/block facts. This class
 * owns the bounded selection, stable tie-breaking, hotbar lease rules, and
 * reflected-world retry policy without depending on Minecraft state.</p>
 */
final class CityBreakerDecisionEngine26 {
    private CityBreakerDecisionEngine26() {
    }

    static long selectBest(List<Candidate> candidates, Limits limits) {
        if (candidates == null || limits == null) {
            return -1L;
        }
        long selected = -1L;
        double selectedTargetDistance = Double.POSITIVE_INFINITY;
        double selectedBlockDistance = Double.POSITIVE_INFINITY;
        float selectedSpeed = Float.NEGATIVE_INFINITY;
        int examined = 0;
        for (Candidate candidate : candidates) {
            if (examined++ >= limits.maximumScans()) {
                break;
            }
            if (!valid(candidate, limits)) {
                continue;
            }
            if (candidate.targetDistance() < selectedTargetDistance
                    || (candidate.targetDistance() == selectedTargetDistance
                    && candidate.blockDistance() < selectedBlockDistance)
                    || (candidate.targetDistance() == selectedTargetDistance
                    && candidate.blockDistance() == selectedBlockDistance
                    && candidate.destroySpeed() > selectedSpeed)
                    || (candidate.targetDistance() == selectedTargetDistance
                    && candidate.blockDistance() == selectedBlockDistance
                    && candidate.destroySpeed() == selectedSpeed
                    && (selected < 0L || candidate.key() < selected))) {
                selected = candidate.key();
                selectedTargetDistance = candidate.targetDistance();
                selectedBlockDistance = candidate.blockDistance();
                selectedSpeed = candidate.destroySpeed();
            }
        }
        return selected;
    }

    static boolean valid(Candidate candidate, Limits limits) {
        return candidate != null
                && candidate.key() >= 0L
                && candidate.targetId() >= 0
                && finiteNonNegative(candidate.targetDistance())
                && finiteNonNegative(candidate.blockDistance())
                && Float.isFinite(candidate.destroySpeed())
                && candidate.destroySpeed() >= 0.0F
                && candidate.toolSlot() >= 0
                && candidate.toolSlot() < 9
                && candidate.targetValid()
                && !candidate.friend()
                && candidate.targetDistance() <= limits.targetRange()
                && candidate.blockDistance() <= limits.mineRange()
                // Enclosed city targets commonly have no entity LOS. The
                // exact obsidian face must remain ray-reachable instead.
                && (candidate.targetLineOfSight()
                || candidate.blockLineOfSight())
                && candidate.blockLineOfSight()
                && candidate.obsidian()
                && candidate.breakable();
    }

    static boolean selectionWasReplaced(
            int originalSlot,
            int appliedSlot,
            int currentSlot
    ) {
        int owned = appliedSlot >= 0 ? appliedSlot : originalSlot;
        return owned >= 0 && currentSlot != owned;
    }

    static int restorationSlot(
            int originalSlot,
            int appliedSlot,
            int currentSlot
    ) {
        return originalSlot >= 0
                && originalSlot < 9
                && appliedSlot >= 0
                && appliedSlot < 9
                && currentSlot == appliedSlot
                ? originalSlot
                : -1;
    }

    static boolean reflectedOpening(boolean air, boolean replaceable) {
        return air || replaceable;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    record Candidate(
            long key,
            int targetId,
            double targetDistance,
            double blockDistance,
            boolean targetValid,
            boolean friend,
            boolean targetLineOfSight,
            boolean blockLineOfSight,
            boolean obsidian,
            boolean breakable,
            int toolSlot,
            float destroySpeed
    ) {
    }

    record Limits(
            int maximumScans,
            double targetRange,
            double mineRange
    ) {
        Limits {
            if (maximumScans <= 0
                    || !Double.isFinite(targetRange)
                    || targetRange <= 0.0
                    || !Double.isFinite(mineRange)
                    || mineRange <= 0.0) {
                throw new IllegalArgumentException("Invalid city limits");
            }
        }
    }

    /**
     * Latches a manual cancellation until an arbiter-granted STOP actually
     * executes. Losing arbitration cannot make the mining lease resume.
     */
    static final class StopLatch {
        private boolean requested;

        void request() {
            requested = true;
        }

        boolean requested() {
            return requested;
        }

        void complete() {
            requested = false;
        }
    }

    /**
     * Reflected block-state confirmation with one or more bounded restarts.
     * A ready retry is consumed only after the live adapter sends START again.
     */
    static final class Confirmation {
        private final int timeoutTicks;
        private final int maximumRetries;
        private Phase phase = Phase.IDLE;
        private long key = -1L;
        private long deadline;
        private int retries;

        Confirmation(int timeoutTicks, int maximumRetries) {
            if (timeoutTicks <= 0 || maximumRetries < 0) {
                throw new IllegalArgumentException("Invalid city confirmation");
            }
            this.timeoutTicks = timeoutTicks;
            this.maximumRetries = maximumRetries;
        }

        boolean begin(long requestedKey, long tick) {
            if (phase != Phase.IDLE
                    || requestedKey < 0L
                    || tick < 0L) {
                return false;
            }
            key = requestedKey;
            retries = 0;
            deadline = add(tick, timeoutTicks);
            phase = Phase.MINING;
            return true;
        }

        Directive observe(
                long observedKey,
                boolean reflectedStateChanged,
                long tick
        ) {
            if (observedKey != key || tick < 0L) {
                return Directive.NONE;
            }
            if (phase == Phase.CONFIRMED) {
                return Directive.CONFIRMED;
            }
            if (phase == Phase.FAILED) {
                return Directive.FAILED;
            }
            if (phase != Phase.MINING && phase != Phase.RETRY_READY) {
                return Directive.NONE;
            }
            if (reflectedStateChanged) {
                phase = Phase.CONFIRMED;
                return Directive.CONFIRMED;
            }
            if (phase == Phase.RETRY_READY) {
                return Directive.RETRY;
            }
            if (tick < deadline) {
                return Directive.CONTINUE;
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
            deadline = add(tick, timeoutTicks);
            phase = Phase.MINING;
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

        private static long add(long tick, int amount) {
            return tick > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE
                    : tick + amount;
        }

        enum Phase {
            IDLE,
            MINING,
            RETRY_READY,
            CONFIRMED,
            FAILED
        }

        enum Directive {
            NONE,
            CONTINUE,
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
}
