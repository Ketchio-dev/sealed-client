package dev.b2tclient.v26.combat;

import java.util.List;

/**
 * Pure Auto Mine tool selection and confirmation timeout logic.
 */
final class MiningDecisionEngine26 {
    static final int MAXIMUM_TOOL_SCANS = 9;

    private MiningDecisionEngine26() {
    }

    static int selectBestTool(
            List<ToolCandidate> candidates,
            int selectedSlot,
            int minimumRemainingDurability
    ) {
        if (candidates == null
                || selectedSlot < 0
                || selectedSlot >= MAXIMUM_TOOL_SCANS
                || minimumRemainingDurability < 0) {
            return -1;
        }
        int bestSlot = selectedSlot;
        double bestScore = Double.NEGATIVE_INFINITY;
        int scanned = 0;
        for (ToolCandidate candidate : candidates) {
            if (scanned++ >= MAXIMUM_TOOL_SCANS || candidate == null) {
                break;
            }
            if (candidate.slot() < 0
                    || candidate.slot() >= MAXIMUM_TOOL_SCANS
                    || !Float.isFinite(candidate.destroySpeed())
                    || candidate.destroySpeed() < 0.0F
                    || (candidate.damageable()
                    && candidate.remainingDurability()
                    <= minimumRemainingDurability)) {
                continue;
            }
            double score = (candidate.correctForDrops() ? 1_000.0 : 0.0)
                    + candidate.destroySpeed();
            if (score > bestScore
                    || (score == bestScore
                    && candidate.slot() == selectedSlot
                    && bestSlot != selectedSlot)
                    || (score == bestScore
                    && bestSlot != selectedSlot
                    && candidate.slot() < bestSlot)) {
                bestSlot = candidate.slot();
                bestScore = score;
            }
        }
        return bestSlot;
    }

    static boolean selectionWasReplaced(
            int originalSlot,
            int appliedSlot,
            int currentSlot
    ) {
        int ownedSlot = appliedSlot >= 0 ? appliedSlot : originalSlot;
        return ownedSlot >= 0 && currentSlot != ownedSlot;
    }

    static int restorationSlot(
            int originalSlot,
            int appliedSlot,
            int currentSlot
    ) {
        return originalSlot >= 0
                && originalSlot < MAXIMUM_TOOL_SCANS
                && appliedSlot >= 0
                && appliedSlot < MAXIMUM_TOOL_SCANS
                && currentSlot == appliedSlot
                ? originalSlot
                : -1;
    }

    static final class Confirmation {
        private final int timeoutTicks;
        private Phase phase = Phase.IDLE;
        private long targetKey = -1L;
        private long deadline;

        Confirmation(int timeoutTicks) {
            if (timeoutTicks <= 0) {
                throw new IllegalArgumentException("timeoutTicks must be positive");
            }
            this.timeoutTicks = timeoutTicks;
        }

        boolean begin(long key, long tick) {
            if (phase != Phase.IDLE || key < 0L || tick < 0L) {
                return false;
            }
            targetKey = key;
            deadline = tick > Long.MAX_VALUE - timeoutTicks
                    ? Long.MAX_VALUE
                    : tick + timeoutTicks;
            phase = Phase.MINING;
            return true;
        }

        Result observe(long key, boolean worldStateChanged, long tick) {
            if (phase != Phase.MINING || key != targetKey || tick < 0L) {
                return Result.NONE;
            }
            if (worldStateChanged) {
                phase = Phase.CONFIRMED;
                return Result.CONFIRMED;
            }
            if (tick >= deadline) {
                phase = Phase.FAILED;
                return Result.FAILED;
            }
            return Result.CONTINUE;
        }

        void reset() {
            phase = Phase.IDLE;
            targetKey = -1L;
            deadline = 0L;
        }

        Phase phase() {
            return phase;
        }

        enum Phase {
            IDLE,
            MINING,
            CONFIRMED,
            FAILED
        }

        enum Result {
            NONE,
            CONTINUE,
            CONFIRMED,
            FAILED
        }
    }

    record ToolCandidate(
            int slot,
            boolean damageable,
            int remainingDurability,
            boolean correctForDrops,
            float destroySpeed
    ) {
    }
}
