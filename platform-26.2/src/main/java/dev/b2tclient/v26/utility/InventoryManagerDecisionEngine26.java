package dev.b2tclient.v26.utility;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure decision engine for conservative stack consolidation.
 *
 * <p>Only a source that can be emptied completely into one earlier
 * main-inventory stack is selected. The engine prepares one logical MERGE
 * transaction; the live adapter performs and verifies the complete
 * source/target PICKUP sequence in a single execute phase.</p>
 */
public final class InventoryManagerDecisionEngine26 {
    public static final Timing DEFAULT_TIMING = new Timing(8, 4);

    private Timing timing;
    private long sessionKey = Long.MIN_VALUE;
    private long sequence;
    private int cooldownTicks;
    private Decision outstanding = Decision.none(0L);

    public InventoryManagerDecisionEngine26() {
        this(DEFAULT_TIMING);
    }

    public InventoryManagerDecisionEngine26(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public void setTiming(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    /**
     * Selects a lossless merge in stable legacy slot order. Hotbar slots and
     * partial transfers are excluded.
     */
    public static Optional<Merge> selectMerge(List<Candidate> candidates) {
        Candidate[] bySlot = new Candidate[36];
        if (candidates == null) {
            return Optional.empty();
        }
        for (Candidate candidate : candidates) {
            if (candidate == null || !candidate.valid()) {
                continue;
            }
            if (bySlot[candidate.inventorySlot()] == null) {
                bySlot[candidate.inventorySlot()] = candidate;
            }
        }

        for (int sourceSlot = 9; sourceSlot < bySlot.length; sourceSlot++) {
            Candidate source = bySlot[sourceSlot];
            if (source == null || !source.stackable()) {
                continue;
            }
            for (int targetSlot = 9; targetSlot < sourceSlot; targetSlot++) {
                Candidate target = bySlot[targetSlot];
                if (!canFullyMerge(source, target)) {
                    continue;
                }
                return Optional.of(new Merge(
                        sourceSlot,
                        targetSlot,
                        source.count(),
                        target.count(),
                        source.maximumCount(),
                        source.equivalenceGroup()
                ));
            }
        }
        return Optional.empty();
    }

    /**
     * Evaluates one tick without mutating Minecraft state.
     */
    public Decision step(Observation observation) {
        sequence++;
        if (observation == null || !observation.valid()) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.INVALID
            );
            return outstanding;
        }
        if (observation.sessionKey() != sessionKey) {
            resetForSession(observation.sessionKey());
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.SESSION_WARMUP
            );
            return outstanding;
        }

        if (!observation.sessionReady()) {
            resetForSession(Long.MIN_VALUE);
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.SESSION
            );
            return outstanding;
        }
        if (observation.manualChange()) {
            cooldownTicks = timing.manualYieldTicks();
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.MANUAL_CHANGE
            );
            return outstanding;
        }

        advanceCooldown();
        if (!observation.enabled()) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.DISABLED
            );
            return outstanding;
        }
        if (!observation.inventoryReady() || !observation.cursorEmpty()) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.INVENTORY
            );
            return outstanding;
        }
        if (cooldownTicks > 0) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.COOLDOWN
            );
            return outstanding;
        }

        outstanding = selectMerge(observation.candidates())
                .map(merge -> new Decision(
                        sequence,
                        Action.MERGE,
                        merge,
                        BlockReason.NONE
                ))
                .orElseGet(() -> Decision.blocked(
                        sequence,
                        BlockReason.NO_MERGE
                ));
        return outstanding;
    }

    /**
     * Starts the normal cooldown only after the latest transaction succeeded.
     */
    public void commit(Decision decision, boolean executed) {
        if (decision == null
                || decision.sequence() != outstanding.sequence()
                || decision.action() != Action.MERGE
                || decision.action() != outstanding.action()
                || !Objects.equals(decision.merge(), outstanding.merge())
                || !executed) {
            return;
        }
        cooldownTicks = timing.actionCooldownTicks();
        outstanding = Decision.none(sequence);
    }

    /**
     * Gives manual or contradictory inventory state a bounded quiet period.
     */
    public void yieldToManualChange() {
        cooldownTicks = timing.manualYieldTicks();
        outstanding = Decision.none(sequence);
    }

    public void reset() {
        sessionKey = Long.MIN_VALUE;
        sequence = 0L;
        cooldownTicks = 0;
        outstanding = Decision.none(0L);
    }

    public Snapshot snapshot() {
        return new Snapshot(cooldownTicks);
    }

    private void resetForSession(long newSessionKey) {
        sessionKey = newSessionKey;
        cooldownTicks = 0;
        outstanding = Decision.none(sequence);
    }

    private void advanceCooldown() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
    }

    private static boolean canFullyMerge(
            Candidate source,
            Candidate target
    ) {
        if (source == null
                || target == null
                || !target.stackable()
                || !source.equivalenceGroup()
                .equals(target.equivalenceGroup())
                || source.maximumCount() != target.maximumCount()) {
            return false;
        }
        return (long) source.count() + target.count()
                <= source.maximumCount();
    }

    public enum Action {
        NONE,
        MERGE
    }

    public enum BlockReason {
        NONE,
        INVALID,
        SESSION,
        SESSION_WARMUP,
        DISABLED,
        INVENTORY,
        MANUAL_CHANGE,
        COOLDOWN,
        NO_MERGE
    }

    public record Timing(int actionCooldownTicks, int manualYieldTicks) {
        public Timing {
            if (actionCooldownTicks < 2 || actionCooldownTicks > 40) {
                throw new IllegalArgumentException(
                        "actionCooldownTicks must be 2..40"
                );
            }
            if (manualYieldTicks < 2 || manualYieldTicks > 40) {
                throw new IllegalArgumentException(
                        "manualYieldTicks must be 2..40"
                );
            }
        }
    }

    public record Candidate(
            int inventorySlot,
            int count,
            int maximumCount,
            boolean stackable,
            String equivalenceGroup
    ) {
        public Candidate {
            equivalenceGroup = equivalenceGroup == null
                    ? ""
                    : equivalenceGroup;
        }

        boolean valid() {
            return inventorySlot >= 9
                    && inventorySlot < 36
                    && count > 0
                    && maximumCount > 1
                    && count <= maximumCount
                    && !equivalenceGroup.isBlank();
        }
    }

    public record Merge(
            int sourceSlot,
            int targetSlot,
            int sourceCount,
            int targetCount,
            int maximumCount,
            String equivalenceGroup
    ) {
        public Merge {
            equivalenceGroup = Objects.requireNonNull(
                    equivalenceGroup,
                    "equivalenceGroup"
            );
            if (sourceSlot < 9
                    || sourceSlot >= 36
                    || targetSlot < 9
                    || targetSlot >= sourceSlot
                    || sourceCount < 1
                    || targetCount < 1
                    || maximumCount < 2
                    || (long) sourceCount + targetCount > maximumCount
                    || equivalenceGroup.isBlank()) {
                throw new IllegalArgumentException("Invalid merge");
            }
        }
    }

    public record Observation(
            long sessionKey,
            boolean enabled,
            boolean sessionReady,
            boolean inventoryReady,
            boolean cursorEmpty,
            boolean manualChange,
            List<Candidate> candidates
    ) {
        public Observation {
            candidates = candidates == null
                    ? List.of()
                    : List.copyOf(candidates);
        }

        boolean valid() {
            return sessionKey != Long.MIN_VALUE;
        }
    }

    public record Decision(
            long sequence,
            Action action,
            Merge merge,
            BlockReason blockReason
    ) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            blockReason = Objects.requireNonNull(
                    blockReason,
                    "blockReason"
            );
        }

        public boolean apply() {
            return action == Action.MERGE
                    && merge != null
                    && blockReason == BlockReason.NONE;
        }

        private static Decision none(long sequence) {
            return blocked(sequence, BlockReason.NONE);
        }

        private static Decision blocked(
                long sequence,
                BlockReason reason
        ) {
            return new Decision(
                    sequence,
                    Action.NONE,
                    null,
                    reason
            );
        }
    }

    public record Snapshot(int cooldownTicks) {
    }
}
