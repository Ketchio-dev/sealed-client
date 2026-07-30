package dev.b2tclient.v26.utility;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft-independent one-shot policy for Chest Swap.
 *
 * <p>An enable edge may produce at most one successful transaction. A held
 * enabled state never oscillates between armor and an elytra: after success,
 * an invalidated transaction, a terminal failure, or the absence of a
 * candidate, the engine remains disarmed until it observes {@code enabled =
 * false}. Temporary arbitration and inventory conflicts are bounded by a
 * small wait window instead of submitting forever.</p>
 */
public final class ChestSwapDecisionEngine26 {
    private static final Comparator<Candidate> ELYTRA_ORDER =
            Comparator.comparingInt(Candidate::remainingDurability)
                    .reversed()
                    .thenComparing(Candidate::hotbar)
                    .thenComparingInt(Candidate::inventorySlot);
    private static final Comparator<Candidate> CHESTPLATE_ORDER =
            Comparator.comparingDouble(Candidate::armorScore)
                    .reversed()
                    .thenComparing(
                            Comparator.comparingInt(
                                    Candidate::remainingDurability
                            ).reversed()
                    )
                    .thenComparing(Candidate::hotbar)
                    .thenComparingInt(Candidate::inventorySlot);

    private Timing timing;
    private long sessionKey = Long.MIN_VALUE;
    private long sequence;
    private boolean armed;
    private boolean lastEnabled;
    private int cooldownTicks;
    private int waitTicks;
    private Terminal terminal = Terminal.NONE;
    private Decision outstanding = Decision.none(0L, BlockReason.DISABLED);

    public ChestSwapDecisionEngine26(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public void setTiming(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
        cooldownTicks = Math.min(
                cooldownTicks,
                Math.max(
                        timing.actionCooldownTicks(),
                        timing.failureCooldownTicks()
                )
        );
        waitTicks = Math.min(waitTicks, timing.maximumWaitTicks());
    }

    /**
     * Chooses the deterministic replacement appropriate for the equipped
     * chest stack.
     */
    public static Optional<Candidate> selectCandidate(
            boolean wearingElytra,
            List<Candidate> candidates,
            int minimumDurability
    ) {
        if (candidates == null
                || minimumDurability < 0
                || minimumDurability > 1_000_000) {
            return Optional.empty();
        }
        CandidateKind desired = wearingElytra
                ? CandidateKind.CHESTPLATE
                : CandidateKind.ELYTRA;
        Comparator<Candidate> order = wearingElytra
                ? CHESTPLATE_ORDER
                : ELYTRA_ORDER;
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(Candidate::valid)
                .filter(candidate -> candidate.kind() == desired)
                .filter(candidate -> candidate.wearable())
                .filter(candidate -> !candidate.cursed())
                .filter(candidate -> !candidate.selectedHotbar())
                .filter(candidate ->
                        candidate.remainingDurability()
                                > minimumDurability)
                .sorted(order)
                .findFirst();
    }

    /**
     * Advances the rising-edge latch and offers at most one transaction.
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
            resetForSession(
                    observation.sessionKey(),
                    observation.enabled()
            );
            lastEnabled = observation.enabled();
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.SESSION_WARMUP
            );
            return outstanding;
        }

        advanceCooldown();
        if (!observation.enabled()) {
            armed = true;
            lastEnabled = false;
            waitTicks = 0;
            terminal = Terminal.NONE;
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.DISABLED
            );
            return outstanding;
        }
        lastEnabled = true;

        if (!observation.sessionReady()) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.SESSION
            );
            return outstanding;
        }
        if (!armed) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.HELD_ENABLED
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
        if (observation.candidateSlot() < 0) {
            finish(Terminal.NO_CANDIDATE, timing.failureCooldownTicks());
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.NO_CANDIDATE
            );
            return outstanding;
        }
        if (!observation.inventoryReady()
                || observation.utilityHotbarOwned()) {
            waitTicks++;
            if (waitTicks >= timing.maximumWaitTicks()) {
                finish(
                        observation.utilityHotbarOwned()
                                ? Terminal.CONFLICT_TIMEOUT
                                : Terminal.INVENTORY_TIMEOUT,
                        timing.failureCooldownTicks()
                );
            }
            outstanding = Decision.blocked(
                    sequence,
                    observation.utilityHotbarOwned()
                            ? BlockReason.UTILITY_CONFLICT
                            : BlockReason.INVENTORY
            );
            return outstanding;
        }

        outstanding = new Decision(
                sequence,
                Action.SWAP,
                observation.candidateSlot(),
                BlockReason.NONE
        );
        return outstanding;
    }

    /**
     * Commits only the most recent offered transaction.
     */
    public void commit(Decision decision, Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (decision == null
                || decision.sequence() != outstanding.sequence()
                || decision.action() != Action.SWAP
                || outstanding.action() != Action.SWAP) {
            return;
        }
        switch (outcome) {
            case APPLIED ->
                    finish(Terminal.APPLIED, timing.actionCooldownTicks());
            case INVALIDATED ->
                    finish(Terminal.MANUAL_YIELD, timing.failureCooldownTicks());
            case FAILED ->
                    finish(Terminal.FAILED, timing.failureCooldownTicks());
            case DENIED -> {
                waitTicks++;
                if (waitTicks >= timing.maximumWaitTicks()) {
                    finish(
                            Terminal.CONFLICT_TIMEOUT,
                            timing.failureCooldownTicks()
                    );
                }
            }
        }
        outstanding = Decision.none(sequence, BlockReason.HELD_ENABLED);
    }

    public void reset() {
        sessionKey = Long.MIN_VALUE;
        sequence = 0L;
        armed = false;
        lastEnabled = false;
        cooldownTicks = 0;
        waitTicks = 0;
        terminal = Terminal.NONE;
        outstanding = Decision.none(0L, BlockReason.DISABLED);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                armed,
                lastEnabled,
                cooldownTicks,
                waitTicks,
                terminal
        );
    }

    private void resetForSession(
            long newSessionKey,
            boolean enabled
    ) {
        sessionKey = newSessionKey;
        armed = !enabled;
        cooldownTicks = 0;
        waitTicks = 0;
        terminal = Terminal.NONE;
    }

    private void advanceCooldown() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
    }

    private void finish(Terminal result, int cooldown) {
        armed = false;
        waitTicks = 0;
        terminal = Objects.requireNonNull(result, "result");
        cooldownTicks = Math.max(cooldownTicks, cooldown);
    }

    public enum CandidateKind {
        ELYTRA,
        CHESTPLATE,
        OTHER
    }

    public enum Action {
        NONE,
        SWAP
    }

    public enum Outcome {
        APPLIED,
        DENIED,
        INVALIDATED,
        FAILED
    }

    public enum Terminal {
        NONE,
        APPLIED,
        NO_CANDIDATE,
        MANUAL_YIELD,
        FAILED,
        INVENTORY_TIMEOUT,
        CONFLICT_TIMEOUT
    }

    public enum BlockReason {
        NONE,
        INVALID,
        SESSION,
        SESSION_WARMUP,
        DISABLED,
        HELD_ENABLED,
        COOLDOWN,
        NO_CANDIDATE,
        INVENTORY,
        UTILITY_CONFLICT
    }

    public record Timing(
            int actionCooldownTicks,
            int failureCooldownTicks,
            int maximumWaitTicks
    ) {
        public Timing {
            if (actionCooldownTicks < 1
                    || actionCooldownTicks > 20) {
                throw new IllegalArgumentException(
                        "actionCooldownTicks must be 1..20"
                );
            }
            if (failureCooldownTicks < 1
                    || failureCooldownTicks > 40) {
                throw new IllegalArgumentException(
                        "failureCooldownTicks must be 1..40"
                );
            }
            if (maximumWaitTicks < 1 || maximumWaitTicks > 20) {
                throw new IllegalArgumentException(
                        "maximumWaitTicks must be 1..20"
                );
            }
        }
    }

    public record Candidate(
            int inventorySlot,
            CandidateKind kind,
            boolean wearable,
            boolean cursed,
            int remainingDurability,
            double armorScore,
            boolean hotbar,
            boolean selectedHotbar
    ) {
        public Candidate {
            kind = Objects.requireNonNull(kind, "kind");
        }

        boolean valid() {
            return inventorySlot >= 0
                    && inventorySlot < 36
                    && remainingDurability >= 0
                    && Double.isFinite(armorScore)
                    && armorScore >= 0.0
                    && (!selectedHotbar || hotbar);
        }
    }

    public record Observation(
            long sessionKey,
            boolean enabled,
            boolean sessionReady,
            boolean inventoryReady,
            boolean utilityHotbarOwned,
            int candidateSlot
    ) {
        boolean valid() {
            return sessionKey != Long.MIN_VALUE
                    && candidateSlot >= -1
                    && candidateSlot < 36;
        }
    }

    public record Decision(
            long sequence,
            Action action,
            int inventorySlot,
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
            return action == Action.SWAP
                    && blockReason == BlockReason.NONE
                    && inventorySlot >= 0
                    && inventorySlot < 36;
        }

        private static Decision none(
                long sequence,
                BlockReason reason
        ) {
            return blocked(sequence, reason);
        }

        private static Decision blocked(
                long sequence,
                BlockReason reason
        ) {
            return new Decision(sequence, Action.NONE, -1, reason);
        }
    }

    public record Snapshot(
            boolean armed,
            boolean lastEnabled,
            int cooldownTicks,
            int waitTicks,
            Terminal terminal
    ) {
    }
}
