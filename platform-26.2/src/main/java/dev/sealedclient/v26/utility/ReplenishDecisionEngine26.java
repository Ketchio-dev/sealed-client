package dev.sealedclient.v26.utility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, deterministic planning and pacing for Minecraft 26.2 Replenish.
 *
 * <p>The live adapter supplies already compared stack pairs. This engine
 * chooses the first eligible hotbar target, then the largest exact matching
 * main-inventory source with a stable lowest-slot tie break. A prepared
 * decision has no state effect until it is committed after arbitration.</p>
 */
public final class ReplenishDecisionEngine26 {
    private static final int MAXIMUM_CANDIDATES = 9 * 27;
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparingInt(Candidate::hotbarSlot)
                    .thenComparing(
                            Comparator.comparingInt(Candidate::sourceCount)
                                    .reversed()
                    )
                    .thenComparingInt(Candidate::sourceInventorySlot);

    private Timing timing;
    private long sessionKey = Long.MIN_VALUE;
    private long sequence;
    private long lastOperationTick = Long.MIN_VALUE;
    private int cooldownTicks;
    private Decision outstanding = Decision.none(
            0L,
            BlockReason.NONE
    );

    public ReplenishDecisionEngine26(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public void setTiming(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    /**
     * Plans at most one exact source-to-target transaction.
     */
    public Decision step(Observation observation) {
        sequence++;
        if (observation == null || !observation.valid()) {
            outstanding = Decision.none(
                    sequence,
                    BlockReason.INVALID
            );
            return outstanding;
        }
        if (!observation.sessionReady()) {
            resetForSession(Long.MIN_VALUE);
            outstanding = Decision.none(
                    sequence,
                    BlockReason.SESSION
            );
            return outstanding;
        }
        if (observation.sessionKey() != sessionKey) {
            resetForSession(observation.sessionKey());
            outstanding = Decision.none(
                    sequence,
                    BlockReason.SESSION_WARMUP
            );
            return outstanding;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (!observation.enabled()) {
            outstanding = Decision.none(
                    sequence,
                    BlockReason.DISABLED
            );
            return outstanding;
        }
        if (observation.manualChange()) {
            cooldownTicks = timing.failureCooldownTicks();
            outstanding = Decision.none(
                    sequence,
                    BlockReason.MANUAL_CHANGE
            );
            return outstanding;
        }
        if (observation.utilityHotbarOwned()) {
            outstanding = Decision.none(
                    sequence,
                    BlockReason.UTILITY_CONFLICT
            );
            return outstanding;
        }
        if (!observation.inventoryReady()) {
            outstanding = Decision.none(
                    sequence,
                    BlockReason.INVENTORY
            );
            return outstanding;
        }
        if (observation.tick() == lastOperationTick) {
            outstanding = Decision.none(
                    sequence,
                    BlockReason.OPERATION_BUDGET
            );
            return outstanding;
        }
        if (cooldownTicks > 0) {
            outstanding = Decision.none(
                    sequence,
                    BlockReason.COOLDOWN
            );
            return outstanding;
        }

        Optional<Candidate> selected = selectCandidate(
                observation.candidates(),
                observation.threshold()
        );
        if (selected.isEmpty()) {
            outstanding = Decision.none(
                    sequence,
                    BlockReason.NO_MATCH
            );
            return outstanding;
        }
        Candidate candidate = selected.orElseThrow();
        int moved = Math.min(
                candidate.sourceCount(),
                candidate.maxStackSize() - candidate.targetCount()
        );
        outstanding = new Decision(
                sequence,
                observation.tick(),
                Action.REPLENISH,
                candidate.sourceInventorySlot(),
                inventoryIndexToMenuSlot(candidate.sourceInventorySlot()),
                candidate.hotbarSlot(),
                inventoryIndexToMenuSlot(candidate.hotbarSlot()),
                candidate.sourceCount(),
                candidate.targetCount(),
                candidate.sourceCount() - moved,
                candidate.targetCount() + moved,
                BlockReason.NONE
        );
        return outstanding;
    }

    /**
     * Commits only the latest decision.
     *
     * <p>A denied claim consumes neither the tick budget nor a cooldown. Once
     * any menu click has been attempted, the tick budget is consumed and a
     * bounded success or failure cooldown prevents immediate retry storms.</p>
     */
    public void commit(Decision decision, CommitResult result) {
        if (decision == null
                || result == null
                || !decision.equals(outstanding)
                || outstanding.action() != Action.REPLENISH) {
            return;
        }
        if (result == CommitResult.APPLIED) {
            lastOperationTick = decision.tick();
            cooldownTicks = timing.actionCooldownTicks();
        } else if (result == CommitResult.FAILED_AFTER_OPERATION) {
            lastOperationTick = decision.tick();
            cooldownTicks = timing.failureCooldownTicks();
        } else if (result == CommitResult.INVALIDATED) {
            cooldownTicks = timing.failureCooldownTicks();
        }
        outstanding = Decision.none(sequence, BlockReason.NONE);
    }

    public void reset() {
        sessionKey = Long.MIN_VALUE;
        sequence = 0L;
        lastOperationTick = Long.MIN_VALUE;
        cooldownTicks = 0;
        outstanding = Decision.none(0L, BlockReason.NONE);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                sessionKey,
                cooldownTicks,
                lastOperationTick,
                outstanding
        );
    }

    /**
     * Selects an exact pair with deterministic target/source ordering.
     */
    public static Optional<Candidate> selectCandidate(
            List<Candidate> candidates,
            int threshold
    ) {
        if (candidates == null
                || candidates.size() > MAXIMUM_CANDIDATES
                || threshold < 1
                || threshold > 63) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(Candidate::valid)
                .filter(Candidate::exactMatch)
                .filter(Candidate::targetStackable)
                .filter(candidate -> candidate.targetCount() <= threshold)
                .filter(candidate ->
                        candidate.targetCount()
                                < candidate.maxStackSize())
                .sorted(CANDIDATE_ORDER)
                .findFirst();
    }

    /**
     * Maps player inventory indices to the vanilla InventoryMenu slot space.
     */
    public static int inventoryIndexToMenuSlot(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= 36) {
            throw new IllegalArgumentException(
                    "Not a player inventory index: " + inventoryIndex
            );
        }
        return inventoryIndex < 9
                ? 36 + inventoryIndex
                : inventoryIndex;
    }

    /**
     * Classifies the only two cursor counts owned by one PICKUP sequence.
     *
     * <p>The carried stack may be the untouched source immediately after the
     * first click, or the exact positive overflow remainder after the target
     * click. Every other intermediate count is treated as foreign state.</p>
     */
    public static boolean ownedRecoveryCandidate(
            int sourceCountBefore,
            int sourceCountAfter,
            int carriedCount,
            boolean exactComponents
    ) {
        return exactComponents
                && sourceCountBefore > 0
                && sourceCountAfter >= 0
                && sourceCountAfter < sourceCountBefore
                && carriedCount > 0
                && (carriedCount == sourceCountBefore
                || sourceCountAfter > 0
                && carriedCount == sourceCountAfter);
    }

    private void resetForSession(long newSessionKey) {
        sessionKey = newSessionKey;
        lastOperationTick = Long.MIN_VALUE;
        cooldownTicks = 0;
        outstanding = Decision.none(sequence, BlockReason.NONE);
    }

    public enum Action {
        NONE,
        REPLENISH
    }

    public enum CommitResult {
        DENIED,
        INVALIDATED,
        APPLIED,
        FAILED_AFTER_OPERATION
    }

    public enum BlockReason {
        NONE,
        INVALID,
        SESSION,
        SESSION_WARMUP,
        DISABLED,
        MANUAL_CHANGE,
        UTILITY_CONFLICT,
        INVENTORY,
        OPERATION_BUDGET,
        COOLDOWN,
        NO_MATCH
    }

    public record Timing(
            int actionCooldownTicks,
            int failureCooldownTicks
    ) {
        public Timing {
            if (actionCooldownTicks < 1
                    || actionCooldownTicks > 20) {
                throw new IllegalArgumentException(
                        "actionCooldownTicks must be 1..20"
                );
            }
            if (failureCooldownTicks < 1
                    || failureCooldownTicks > 20) {
                throw new IllegalArgumentException(
                        "failureCooldownTicks must be 1..20"
                );
            }
        }
    }

    /**
     * One possible exact target/source pair supplied by the live adapter.
     */
    public record Candidate(
            int hotbarSlot,
            int sourceInventorySlot,
            int targetCount,
            int sourceCount,
            int maxStackSize,
            boolean targetStackable,
            boolean exactMatch
    ) {
        boolean valid() {
            return hotbarSlot >= 0
                    && hotbarSlot < 9
                    && sourceInventorySlot >= 9
                    && sourceInventorySlot < 36
                    && targetCount > 0
                    && sourceCount > 0
                    && maxStackSize > 1
                    && maxStackSize <= 99
                    && targetCount <= maxStackSize
                    && sourceCount <= maxStackSize;
        }
    }

    public record Observation(
            long sessionKey,
            long tick,
            boolean enabled,
            boolean sessionReady,
            boolean inventoryReady,
            boolean manualChange,
            boolean utilityHotbarOwned,
            int threshold,
            List<Candidate> candidates
    ) {
        public Observation {
            candidates = candidates == null
                    ? null
                    : Collections.unmodifiableList(
                            new ArrayList<>(candidates)
                    );
        }

        boolean valid() {
            return sessionKey != Long.MIN_VALUE
                    && tick >= 0
                    && threshold >= 1
                    && threshold <= 63
                    && candidates != null
                    && candidates.size() <= MAXIMUM_CANDIDATES;
        }
    }

    public record Decision(
            long sequence,
            long tick,
            Action action,
            int sourceInventorySlot,
            int sourceMenuSlot,
            int targetHotbarSlot,
            int targetMenuSlot,
            int sourceCountBefore,
            int targetCountBefore,
            int sourceCountAfter,
            int targetCountAfter,
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
            return action == Action.REPLENISH
                    && blockReason == BlockReason.NONE
                    && tick >= 0
                    && sourceInventorySlot >= 9
                    && sourceInventorySlot < 36
                    && sourceMenuSlot
                            == inventoryIndexToMenuSlot(
                            sourceInventorySlot
                    )
                    && targetHotbarSlot >= 0
                    && targetHotbarSlot < 9
                    && targetMenuSlot
                            == inventoryIndexToMenuSlot(
                            targetHotbarSlot
                    )
                    && sourceCountBefore > 0
                    && targetCountBefore > 0
                    && sourceCountAfter >= 0
                    && sourceCountAfter < sourceCountBefore
                    && targetCountAfter > targetCountBefore;
        }

        private static Decision none(
                long sequence,
                BlockReason reason
        ) {
            return new Decision(
                    sequence,
                    -1L,
                    Action.NONE,
                    -1,
                    -1,
                    -1,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    reason
            );
        }
    }

    public record Snapshot(
            long sessionKey,
            int cooldownTicks,
            long lastOperationTick,
            Decision outstanding
    ) {
    }
}
