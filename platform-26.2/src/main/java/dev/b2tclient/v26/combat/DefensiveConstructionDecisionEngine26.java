package dev.b2tclient.v26.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure, bounded decisions shared by the Minecraft 26.2 defensive construction
 * adapter.
 *
 * <p>The live adapter is responsible for reading blocks and entities. This
 * class accepts only immutable observations, enforces the scan budget, and
 * deterministically selects a safe candidate. Keeping this layer free of
 * Minecraft classes makes the safety and ownership rules directly testable.</p>
 */
final class DefensiveConstructionDecisionEngine26 {
    static final int HOTBAR_SIZE = 9;

    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparingInt(Candidate::order)
                    .thenComparingDouble(Candidate::targetDistanceSquared)
                    .thenComparingDouble(Candidate::selfDistanceSquared)
                    .thenComparingLong(Candidate::key);

    private DefensiveConstructionDecisionEngine26() {
    }

    /**
     * Selects one candidate while inspecting no more than {@code maximumScans}
     * input observations.
     */
    static Candidate selectBest(
            Module module,
            List<Candidate> candidates,
            int maximumScans,
            double maximumReachSquared
    ) {
        if (module == null) {
            return null;
        }
        return selectBest(
                candidates,
                ModeLimits.tryCreate(
                        module,
                        maximumScans,
                        maximumReachSquared
                )
        );
    }

    /**
     * Mode-bound overload used by the live service. Binding the module to its
     * reach and scan limits prevents a simultaneously enabled mode from
     * supplying another mode's range.
     */
    static Candidate selectBest(
            List<Candidate> candidates,
            ModeLimits limits
    ) {
        if (candidates == null || limits == null) {
            return null;
        }
        Candidate best = null;
        int scanned = 0;
        for (Candidate candidate : candidates) {
            if (scanned++ >= limits.maximumScans()) {
                break;
            }
            if (!safe(
                    limits.module(),
                    candidate,
                    limits.maximumReachSquared()
            )) {
                continue;
            }
            if (best == null
                    || CANDIDATE_ORDER.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    static boolean safe(
            Module module,
            Candidate candidate,
            double maximumReachSquared
    ) {
        if (module == null
                || candidate == null
                || candidate.module() != module
                || !Double.isFinite(maximumReachSquared)
                || maximumReachSquared < 0.0
                || candidate.selfDistanceSquared() > maximumReachSquared
                || !candidate.replaceable()
                || !candidate.supported()
                || !candidate.collisionFree()) {
            return false;
        }
        if ((module == Module.AUTO_TRAP || module == Module.HOLE_FILL)
                && !candidate.targetEligible()) {
            return false;
        }
        return module != Module.HOLE_FILL || candidate.blastSafeHole();
    }

    /**
     * Detects a manual or foreign hotbar selection after the adapter applied a
     * slot. A replaced selection is never overwritten during cleanup.
     */
    static boolean selectionWasReplaced(
            int originalSlot,
            int appliedSlot,
            int currentSlot
    ) {
        int owned = validHotbarSlot(appliedSlot)
                ? appliedSlot
                : originalSlot;
        return validHotbarSlot(owned) && currentSlot != owned;
    }

    /**
     * Returns the original slot only when the adapter still owns the exact slot
     * it applied.
     */
    static int restorationSlot(
            int originalSlot,
            int appliedSlot,
            int currentSlot
    ) {
        return validHotbarSlot(originalSlot)
                && validHotbarSlot(appliedSlot)
                && currentSlot == appliedSlot
                ? originalSlot
                : -1;
    }

    static boolean validHotbarSlot(int slot) {
        return slot >= 0 && slot < HOTBAR_SIZE;
    }

    enum Module {
        SURROUND,
        HOLE_FILL,
        SELF_TRAP,
        AUTO_TRAP,
        BURROW
    }

    record Candidate(
            long key,
            Module module,
            int order,
            double selfDistanceSquared,
            double targetDistanceSquared,
            boolean replaceable,
            boolean supported,
            boolean collisionFree,
            boolean targetEligible,
            boolean blastSafeHole
    ) {
        Candidate {
            if (key < 0L
                    || order < 0
                    || !Double.isFinite(selfDistanceSquared)
                    || selfDistanceSquared < 0.0
                    || !Double.isFinite(targetDistanceSquared)
                    || targetDistanceSquared < 0.0) {
                throw new IllegalArgumentException(
                        "Invalid defensive construction candidate"
                );
            }
            module = Objects.requireNonNull(module, "module");
        }
    }

    record ModeLimits(
            Module module,
            int maximumScans,
            double maximumReachSquared
    ) {
        ModeLimits {
            module = Objects.requireNonNull(module, "module");
            if (maximumScans <= 0
                    || !Double.isFinite(maximumReachSquared)
                    || maximumReachSquared < 0.0) {
                throw new IllegalArgumentException(
                        "Invalid defensive construction mode limits"
                );
            }
        }

        static ModeLimits tryCreate(
                Module module,
                int maximumScans,
                double maximumReachSquared
        ) {
            if (module == null
                    || maximumScans <= 0
                    || !Double.isFinite(maximumReachSquared)
                    || maximumReachSquared < 0.0) {
                return null;
            }
            return new ModeLimits(
                    module,
                    maximumScans,
                    maximumReachSquared
            );
        }
    }

    /**
     * Conservative vanilla Burrow state machine. It never treats an
     * interaction as success; completion requires a later server-reflected
     * block confirmation from the live adapter.
     */
    static final class BurrowStateMachine {
        private Phase phase = Phase.IDLE;
        private long targetKey = -1L;
        private double startY;
        private long deadline;

        boolean begin(
                long key,
                double requestedStartY,
                long tick,
                int timeoutTicks
        ) {
            if (phase != Phase.IDLE
                    || key < 0L
                    || !Double.isFinite(requestedStartY)
                    || tick < 0L
                    || timeoutTicks <= 0) {
                return false;
            }
            targetKey = key;
            startY = requestedStartY;
            deadline = saturatingAdd(tick, timeoutTicks);
            phase = Phase.READY;
            return true;
        }

        Directive evaluate(
                double currentY,
                boolean onGround,
                boolean targetReplaceable,
                long tick,
                boolean autoJump,
                double minimumRise
        ) {
            if (phase == Phase.IDLE
                    || phase == Phase.AWAITING_CONFIRMATION
                    || phase == Phase.CONFIRMED
                    || phase == Phase.FAILED
                    || !Double.isFinite(currentY)
                    || !Double.isFinite(minimumRise)
                    || minimumRise <= 0.0
                    || tick < 0L) {
                return Directive.NONE;
            }
            if (tick >= deadline || !targetReplaceable) {
                phase = Phase.FAILED;
                return Directive.FAILED;
            }
            if (phase == Phase.READY) {
                if (currentY >= startY + minimumRise) {
                    phase = Phase.READY_TO_PLACE;
                    return Directive.PLACE;
                }
                if (!autoJump) {
                    phase = Phase.WAITING_FOR_RISE;
                    return Directive.WAIT;
                }
                return onGround ? Directive.JUMP : Directive.WAIT;
            }
            if (phase == Phase.WAITING_FOR_RISE) {
                if (currentY >= startY + minimumRise) {
                    phase = Phase.READY_TO_PLACE;
                    return Directive.PLACE;
                }
                return Directive.WAIT;
            }
            if (phase == Phase.READY_TO_PLACE) {
                return currentY >= startY + minimumRise
                        ? Directive.PLACE
                        : Directive.WAIT;
            }
            return Directive.NONE;
        }

        boolean markJumpSent() {
            if (phase != Phase.READY) {
                return false;
            }
            phase = Phase.WAITING_FOR_RISE;
            return true;
        }

        boolean markPlacementSent() {
            if (phase != Phase.READY_TO_PLACE) {
                return false;
            }
            phase = Phase.AWAITING_CONFIRMATION;
            return true;
        }

        boolean confirm(long key) {
            if (phase != Phase.AWAITING_CONFIRMATION || key != targetKey) {
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
            targetKey = -1L;
            startY = 0.0;
            deadline = 0L;
        }

        Snapshot snapshot() {
            return new Snapshot(phase, targetKey, startY, deadline);
        }

        private static long saturatingAdd(long value, int amount) {
            return value > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE
                    : value + amount;
        }

        enum Phase {
            IDLE,
            READY,
            WAITING_FOR_RISE,
            READY_TO_PLACE,
            AWAITING_CONFIRMATION,
            CONFIRMED,
            FAILED
        }

        enum Directive {
            NONE,
            WAIT,
            JUMP,
            PLACE,
            FAILED
        }

        record Snapshot(
                Phase phase,
                long targetKey,
                double startY,
                long deadline
        ) {
        }
    }
}
