package dev.b2tclient.v26.movement;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure candidate selection and ownership state machine for Elytra Swap.
 *
 * <p>The engine never reads or mutates a Minecraft inventory. The live adapter
 * supplies exact observations of the equipped stack and the source slot. Once
 * an equip transaction is confirmed, restoration is offered only while both
 * locations still contain the stacks placed there by that transaction. Any
 * manual equipment or source-slot change abandons ownership instead of
 * overwriting the player.</p>
 */
public final class ElytraSwapDecisionEngine26 {
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparingInt(Candidate::remainingDurability)
                    .reversed()
                    .thenComparing(Candidate::hotbar)
                    .thenComparingInt(Candidate::inventorySlot);

    private Timing timing;

    private long sessionKey = Long.MIN_VALUE;
    private long sequence;
    private Phase phase = Phase.IDLE;
    private int ownedSourceSlot = -1;
    private boolean restoreRequired;
    private int confirmationTicks;
    private int stableConfirmationTicks;
    private int cooldownTicks;
    private boolean suppressedUntilGround;
    private Decision outstanding = Decision.none(0L, Phase.IDLE);

    public ElytraSwapDecisionEngine26(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public void setTiming(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    /**
     * Selects the healthiest eligible elytra with deterministic tie-breaking.
     *
     * <p>The currently selected hotbar slot is never selected as a transaction
     * source. This prevents an automatic equipment action from replacing the
     * item the player is actively holding.</p>
     */
    public static Optional<Candidate> selectBestElytra(
            List<Candidate> candidates,
            int minimumDurability
    ) {
        if (candidates == null
                || minimumDurability < 0
                || minimumDurability > 1_000_000) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(Candidate::valid)
                .filter(Candidate::elytra)
                .filter(Candidate::glider)
                .filter(candidate -> !candidate.cursed())
                .filter(candidate -> !candidate.selectedHotbar())
                .filter(candidate ->
                        candidate.remainingDurability() > minimumDurability)
                .sorted(CANDIDATE_ORDER)
                .findFirst();
    }

    /**
     * Advances ownership and prepares at most one inventory transaction.
     *
     * <p>The returned decision has no effect until {@link #commit(Decision,
     * boolean)} is called. An arbiter denial therefore cannot move the state
     * into an unconfirmed transaction.</p>
     */
    public Decision step(Observation observation) {
        sequence++;
        if (observation == null || !observation.valid()) {
            outstanding = Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.INVALID
            );
            return outstanding;
        }
        if (observation.sessionKey() != sessionKey) {
            resetForSession(observation.sessionKey());
            outstanding = Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.SESSION_WARMUP
            );
            return outstanding;
        }

        advanceTimers();
        if (!observation.sessionReady()) {
            resetForSession(Long.MIN_VALUE);
            outstanding = Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.SESSION
            );
            return outstanding;
        }

        if (phase == Phase.AWAITING_EQUIP) {
            observeEquipConfirmation(observation);
        } else if (phase == Phase.OWNED) {
            observeOwnedStacks(observation);
        } else if (phase == Phase.AWAITING_RESTORE) {
            observeRestoreConfirmation(observation);
        }

        if (suppressedUntilGround && observation.onGround()) {
            suppressedUntilGround = false;
        }

        Decision decision = decideAction(observation);
        outstanding = decision;
        return decision;
    }

    /**
     * Commits only the latest prepared action.
     */
    public void commit(Decision decision, boolean executed) {
        if (decision == null
                || decision.sequence() != outstanding.sequence()
                || decision.action() == Action.NONE
                || decision.action() != outstanding.action()) {
            return;
        }
        if (!executed) {
            outstanding = Decision.none(sequence, phase);
            return;
        }

        if (decision.action() == Action.EQUIP) {
            phase = Phase.AWAITING_EQUIP;
            ownedSourceSlot = decision.inventorySlot();
            restoreRequired = decision.restoreRequired();
            confirmationTicks = timing.confirmationTimeoutTicks();
            stableConfirmationTicks = 0;
        } else {
            phase = Phase.AWAITING_RESTORE;
            confirmationTicks = timing.confirmationTimeoutTicks();
            stableConfirmationTicks = 0;
        }
        outstanding = Decision.none(sequence, phase);
    }

    public void reset() {
        sessionKey = Long.MIN_VALUE;
        sequence = 0L;
        phase = Phase.IDLE;
        clearLease();
        cooldownTicks = 0;
        suppressedUntilGround = false;
        outstanding = Decision.none(0L, phase);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                phase,
                ownedSourceSlot,
                restoreRequired,
                confirmationTicks,
                stableConfirmationTicks,
                cooldownTicks,
                suppressedUntilGround
        );
    }

    private Decision decideAction(Observation observation) {
        if (phase == Phase.AWAITING_EQUIP
                || phase == Phase.AWAITING_RESTORE) {
            return Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.CONFIRMING
            );
        }
        if (phase == Phase.OWNED) {
            if (!restoreRequired || !observation.restoreArmor()) {
                abandonLease(!observation.onGround());
                return Decision.blocked(
                        sequence,
                        phase,
                        BlockReason.NO_RESTORE_REQUIRED
                );
            }
            if (!observation.onGround()) {
                return Decision.blocked(
                        sequence,
                        phase,
                        observation.enabled()
                                ? BlockReason.WAITING_FOR_LANDING
                                : BlockReason.DISABLED_IN_FLIGHT
                );
            }
            if (!observation.inventoryReady()) {
                return Decision.blocked(
                        sequence,
                        phase,
                        BlockReason.INVENTORY
                );
            }
            return new Decision(
                    sequence,
                    Action.RESTORE,
                    ownedSourceSlot,
                    true,
                    phase,
                    BlockReason.NONE
            );
        }

        if (!observation.enabled()) {
            return Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.DISABLED
            );
        }
        if (suppressedUntilGround) {
            return Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.MANUAL_OVERRIDE
            );
        }
        if (cooldownTicks > 0) {
            return Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.COOLDOWN
            );
        }
        if (!observation.inventoryReady()) {
            return Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.INVENTORY
            );
        }
        if (observation.onGround()
                || observation.unsafeEnvironment()
                || observation.wearingAnyElytra()
                || observation.fallDistance()
                < observation.minimumFallDistance()) {
            return Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.FLIGHT_STATE
            );
        }
        if (observation.candidateSlot() < 0) {
            return Decision.blocked(
                    sequence,
                    phase,
                    BlockReason.NO_ELYTRA
            );
        }
        return new Decision(
                sequence,
                Action.EQUIP,
                observation.candidateSlot(),
                observation.displacedChestPresent()
                        && observation.restoreArmor(),
                phase,
                BlockReason.NONE
        );
    }

    private void observeEquipConfirmation(Observation observation) {
        if (observation.ownershipContradicted()) {
            abandonLease(true);
            return;
        }
        if (observation.wearingOwnedElytra()
                && observation.sourceOwnershipIntact()) {
            stableConfirmationTicks++;
            if (stableConfirmationTicks
                    >= timing.stableConfirmationTicks()) {
                phase = Phase.OWNED;
                confirmationTicks = 0;
                stableConfirmationTicks = 0;
            }
            return;
        }
        stableConfirmationTicks = 0;
        if (confirmationTicks <= 0) {
            abandonLease(true);
        }
    }

    private void observeOwnedStacks(Observation observation) {
        if (!observation.wearingOwnedElytra()
                || !observation.sourceOwnershipIntact()) {
            abandonLease(true);
        }
    }

    private void observeRestoreConfirmation(Observation observation) {
        if (observation.restoreConfirmed()) {
            stableConfirmationTicks++;
            if (stableConfirmationTicks
                    >= timing.stableConfirmationTicks()) {
                clearLease();
                cooldownTicks = timing.actionCooldownTicks();
                phase = Phase.IDLE;
            }
            return;
        }
        stableConfirmationTicks = 0;
        if (observation.ownershipContradicted()
                || confirmationTicks <= 0) {
            abandonLease(false);
        }
    }

    private void advanceTimers() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if ((phase == Phase.AWAITING_EQUIP
                || phase == Phase.AWAITING_RESTORE)
                && confirmationTicks > 0) {
            confirmationTicks--;
        }
    }

    private void resetForSession(long newSessionKey) {
        sessionKey = newSessionKey;
        phase = Phase.IDLE;
        clearLease();
        cooldownTicks = 0;
        suppressedUntilGround = false;
    }

    private void abandonLease(boolean suppress) {
        clearLease();
        phase = Phase.IDLE;
        cooldownTicks = timing.failureCooldownTicks();
        suppressedUntilGround |= suppress;
    }

    private void clearLease() {
        ownedSourceSlot = -1;
        restoreRequired = false;
        confirmationTicks = 0;
        stableConfirmationTicks = 0;
    }

    public enum Phase {
        IDLE,
        AWAITING_EQUIP,
        OWNED,
        AWAITING_RESTORE
    }

    public enum Action {
        NONE,
        EQUIP,
        RESTORE
    }

    public enum BlockReason {
        NONE,
        INVALID,
        SESSION,
        SESSION_WARMUP,
        DISABLED,
        DISABLED_IN_FLIGHT,
        INVENTORY,
        FLIGHT_STATE,
        NO_ELYTRA,
        CONFIRMING,
        WAITING_FOR_LANDING,
        NO_RESTORE_REQUIRED,
        MANUAL_OVERRIDE,
        COOLDOWN
    }

    public record Timing(
            int confirmationTimeoutTicks,
            int stableConfirmationTicks,
            int actionCooldownTicks,
            int failureCooldownTicks
    ) {
        public Timing {
            if (confirmationTimeoutTicks < 2
                    || confirmationTimeoutTicks > 200) {
                throw new IllegalArgumentException(
                        "confirmationTimeoutTicks must be 2..200"
                );
            }
            if (stableConfirmationTicks < 1
                    || stableConfirmationTicks > 10
                    || stableConfirmationTicks
                    >= confirmationTimeoutTicks) {
                throw new IllegalArgumentException(
                        "stableConfirmationTicks must be 1..10 and below timeout"
                );
            }
            if (actionCooldownTicks < 1 || actionCooldownTicks > 100) {
                throw new IllegalArgumentException(
                        "actionCooldownTicks must be 1..100"
                );
            }
            if (failureCooldownTicks < 1 || failureCooldownTicks > 200) {
                throw new IllegalArgumentException(
                        "failureCooldownTicks must be 1..200"
                );
            }
        }
    }

    public record Candidate(
            int inventorySlot,
            boolean elytra,
            boolean glider,
            int remainingDurability,
            boolean cursed,
            boolean hotbar,
            boolean selectedHotbar
    ) {
        boolean valid() {
            return inventorySlot >= 0
                    && inventorySlot < 36
                    && remainingDurability >= 0
                    && (!selectedHotbar || hotbar);
        }
    }

    public record Observation(
            long sessionKey,
            boolean enabled,
            boolean sessionReady,
            boolean inventoryReady,
            boolean onGround,
            boolean unsafeEnvironment,
            double fallDistance,
            double minimumFallDistance,
            int candidateSlot,
            boolean displacedChestPresent,
            boolean restoreArmor,
            boolean wearingAnyElytra,
            boolean wearingOwnedElytra,
            boolean sourceOwnershipIntact,
            boolean restoreConfirmed,
            boolean ownershipContradicted
    ) {
        boolean valid() {
            return sessionKey != Long.MIN_VALUE
                    && Double.isFinite(fallDistance)
                    && fallDistance >= 0.0
                    && Double.isFinite(minimumFallDistance)
                    && minimumFallDistance >= 0.0
                    && minimumFallDistance <= 32.0
                    && candidateSlot >= -1
                    && candidateSlot < 36;
        }
    }

    public record Decision(
            long sequence,
            Action action,
            int inventorySlot,
            boolean restoreRequired,
            Phase phase,
            BlockReason blockReason
    ) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            phase = Objects.requireNonNull(phase, "phase");
            blockReason = Objects.requireNonNull(blockReason, "blockReason");
        }

        public boolean apply() {
            return action != Action.NONE
                    && blockReason == BlockReason.NONE
                    && inventorySlot >= 0
                    && inventorySlot < 36;
        }

        private static Decision none(long sequence, Phase phase) {
            return blocked(sequence, phase, BlockReason.NONE);
        }

        private static Decision blocked(
                long sequence,
                Phase phase,
                BlockReason reason
        ) {
            return new Decision(
                    sequence,
                    Action.NONE,
                    -1,
                    false,
                    phase,
                    reason
            );
        }
    }

    public record Snapshot(
            Phase phase,
            int ownedSourceSlot,
            boolean restoreRequired,
            int confirmationTicks,
            int stableConfirmationTicks,
            int cooldownTicks,
            boolean suppressedUntilGround
    ) {
    }
}
