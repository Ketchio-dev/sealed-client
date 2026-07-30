package dev.sealedclient.v26.utility;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, deterministic Auto Armor selection and timing state machine.
 *
 * <p>The live adapter supplies immutable armor observations. This engine does
 * not read or mutate a Minecraft inventory. It emits at most one upgrade for
 * a unique client tick, and an emitted upgrade has no timing effect until it
 * is committed after arbitration and live-state revalidation.</p>
 *
 * <p>Session changes discard every outstanding decision and require one
 * warm-up observation. Manual input or a stale prepared transaction starts a
 * short bounded yield window instead of fighting the player.</p>
 */
public final class AutoArmorDecisionEngine26 {
    private static final Comparator<Upgrade> UPGRADE_ORDER =
            Comparator.comparingDouble(Upgrade::improvement)
                    .reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                    (Upgrade upgrade) ->
                                            upgrade.candidate().score()
                            ).reversed()
                    )
                    .thenComparing(
                            Comparator.comparingInt(
                                    (Upgrade upgrade) ->
                                            upgrade.candidate()
                                                    .remainingDurability()
                            ).reversed()
                    )
                    .thenComparing(
                            upgrade ->
                                    upgrade.candidate().inventorySlot() < 9
                    )
                    .thenComparingInt(
                            upgrade -> upgrade.armorSlot().ordinal()
                    )
                    .thenComparingInt(
                            upgrade ->
                                    upgrade.candidate().inventorySlot()
                    );

    private Timing timing;
    private long sessionKey = Long.MIN_VALUE;
    private long sequence;
    private long lastStepTick = Long.MIN_VALUE;
    private int cooldownTicks;
    private int manualYieldTicks;
    private Decision outstanding = Decision.none(
            0L,
            Long.MIN_VALUE,
            BlockReason.INVALID
    );

    public AutoArmorDecisionEngine26(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public void setTiming(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
        cooldownTicks = Math.min(
                cooldownTicks,
                timing.actionCooldownTicks()
        );
        manualYieldTicks = Math.min(
                manualYieldTicks,
                timing.manualYieldTicks()
        );
    }

    /**
     * Chooses the largest real armor improvement with stable tie-breaking.
     */
    public static Optional<Upgrade> selectBestUpgrade(
            List<EquippedArmor> equipped,
            List<Candidate> candidates,
            boolean preserveElytra,
            int minimumRemainingDurability,
            double minimumImprovement
    ) {
        if (equipped == null
                || candidates == null
                || minimumRemainingDurability < 0
                || minimumRemainingDurability > 1_000_000
                || !Double.isFinite(minimumImprovement)
                || minimumImprovement < 0.0
                || minimumImprovement > 1_000_000.0) {
            return Optional.empty();
        }

        Map<ArmorSlot, EquippedArmor> equippedBySlot =
                new EnumMap<>(ArmorSlot.class);
        for (EquippedArmor armor : equipped) {
            if (armor != null && armor.valid()) {
                if (equippedBySlot.putIfAbsent(
                        armor.armorSlot(),
                        armor
                ) != null) {
                    return Optional.empty();
                }
            }
        }

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(Candidate::valid)
                .filter(candidate ->
                        candidate.remainingDurability()
                                > minimumRemainingDurability)
                .filter(candidate -> !candidate.bindingCursed())
                .filter(candidate -> !candidate.selectedHotbar())
                .map(candidate -> {
                    EquippedArmor current =
                            equippedBySlot.get(candidate.armorSlot());
                    if (current == null
                            || current.bindingCursed()
                            || (preserveElytra
                            && current.armorSlot() == ArmorSlot.CHEST
                            && current.elytra())) {
                        return null;
                    }
                    double improvement =
                            candidate.score() - current.score();
                    return Double.isFinite(improvement)
                            && improvement > minimumImprovement
                            ? new Upgrade(
                                    candidate.armorSlot(),
                                    candidate,
                                    current,
                                    improvement
                            )
                            : null;
                })
                .filter(Objects::nonNull)
                .sorted(UPGRADE_ORDER)
                .findFirst();
    }

    /**
     * Advances timers once and prepares at most one upgrade per unique tick.
     */
    public Decision step(Observation observation) {
        sequence++;
        if (observation == null || !observation.valid()) {
            outstanding = Decision.blocked(
                    sequence,
                    observation == null
                            ? Long.MIN_VALUE
                            : observation.tick(),
                    BlockReason.INVALID
            );
            return outstanding;
        }

        if (observation.sessionKey() != sessionKey) {
            resetForSession(observation.sessionKey());
            lastStepTick = observation.tick();
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.SESSION_WARMUP
            );
            return outstanding;
        }
        if (!observation.sessionReady()) {
            resetForSession(Long.MIN_VALUE);
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.SESSION
            );
            return outstanding;
        }
        if (observation.tick() == lastStepTick) {
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.DUPLICATE_TICK
            );
            return outstanding;
        }

        lastStepTick = observation.tick();
        advanceTimers();
        if (observation.manualChange()) {
            manualYieldTicks = timing.manualYieldTicks();
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.MANUAL_CHANGE
            );
            return outstanding;
        }
        if (!observation.enabled()) {
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.DISABLED
            );
            return outstanding;
        }
        if (observation.utilityHotbarOwned()) {
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.UTILITY_HOTBAR
            );
            return outstanding;
        }
        if (!observation.inventoryReady()) {
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.INVENTORY
            );
            return outstanding;
        }
        if (manualYieldTicks > 0) {
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.MANUAL_YIELD
            );
            return outstanding;
        }
        if (cooldownTicks > 0) {
            outstanding = Decision.blocked(
                    sequence,
                    observation.tick(),
                    BlockReason.COOLDOWN
            );
            return outstanding;
        }

        Optional<Upgrade> upgrade = selectBestUpgrade(
                observation.equipped(),
                observation.candidates(),
                observation.preserveElytra(),
                observation.minimumRemainingDurability(),
                observation.minimumImprovement()
        );
        outstanding = upgrade
                .map(value -> Decision.equip(
                        sequence,
                        observation.tick(),
                        value
                ))
                .orElseGet(() -> Decision.blocked(
                        sequence,
                        observation.tick(),
                        BlockReason.NO_UPGRADE
                ));
        return outstanding;
    }

    /**
     * Commits only the most recently prepared decision.
     */
    public void commit(Decision decision, Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (decision == null
                || decision.sequence() != outstanding.sequence()
                || decision.tick() != outstanding.tick()
                || decision.upgrade() == null
                || decision.upgrade() != outstanding.upgrade()) {
            return;
        }
        if (outcome == Outcome.EXECUTED) {
            cooldownTicks = timing.actionCooldownTicks();
        } else if (outcome == Outcome.STALE) {
            manualYieldTicks = timing.manualYieldTicks();
        }
        outstanding = Decision.none(
                sequence,
                lastStepTick,
                BlockReason.NONE
        );
    }

    public void reset() {
        sessionKey = Long.MIN_VALUE;
        sequence = 0L;
        lastStepTick = Long.MIN_VALUE;
        cooldownTicks = 0;
        manualYieldTicks = 0;
        outstanding = Decision.none(
                0L,
                Long.MIN_VALUE,
                BlockReason.INVALID
        );
    }

    public Snapshot snapshot() {
        return new Snapshot(
                sessionKey,
                lastStepTick,
                cooldownTicks,
                manualYieldTicks,
                outstanding.apply()
        );
    }

    private void resetForSession(long requestedSessionKey) {
        sessionKey = requestedSessionKey;
        lastStepTick = Long.MIN_VALUE;
        cooldownTicks = 0;
        manualYieldTicks = 0;
        outstanding = Decision.none(
                sequence,
                Long.MIN_VALUE,
                BlockReason.NONE
        );
    }

    private void advanceTimers() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (manualYieldTicks > 0) {
            manualYieldTicks--;
        }
    }

    public enum ArmorSlot {
        HEAD,
        CHEST,
        LEGS,
        FEET
    }

    public enum BlockReason {
        NONE,
        INVALID,
        SESSION,
        SESSION_WARMUP,
        DUPLICATE_TICK,
        DISABLED,
        INVENTORY,
        UTILITY_HOTBAR,
        MANUAL_CHANGE,
        MANUAL_YIELD,
        COOLDOWN,
        NO_UPGRADE
    }

    public enum Outcome {
        EXECUTED,
        DENIED,
        STALE
    }

    public record Candidate(
            int inventorySlot,
            ArmorSlot armorSlot,
            double score,
            int remainingDurability,
            boolean bindingCursed,
            boolean selectedHotbar
    ) {
        boolean valid() {
            return inventorySlot >= 0
                    && inventorySlot < 36
                    && armorSlot != null
                    && Double.isFinite(score)
                    && score >= 0.0
                    && score <= 1_000_000.0
                    && remainingDurability >= 0;
        }
    }

    public record EquippedArmor(
            ArmorSlot armorSlot,
            double score,
            boolean empty,
            boolean elytra,
            boolean bindingCursed
    ) {
        boolean valid() {
            return armorSlot != null
                    && Double.isFinite(score)
                    && score >= -1.0
                    && score <= 1_000_000.0
                    && (empty || elytra || score >= 0.0)
                    && !(empty && (elytra || bindingCursed));
        }
    }

    public record Upgrade(
            ArmorSlot armorSlot,
            Candidate candidate,
            EquippedArmor equipped,
            double improvement
    ) {
        public Upgrade {
            Objects.requireNonNull(armorSlot, "armorSlot");
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(equipped, "equipped");
            if (candidate.armorSlot() != armorSlot
                    || equipped.armorSlot() != armorSlot
                    || !Double.isFinite(improvement)
                    || improvement <= 0.0) {
                throw new IllegalArgumentException(
                        "Upgrade must describe one positive armor replacement"
                );
            }
        }
    }

    public record Observation(
            long sessionKey,
            long tick,
            boolean enabled,
            boolean sessionReady,
            boolean inventoryReady,
            boolean utilityHotbarOwned,
            boolean manualChange,
            boolean preserveElytra,
            int minimumRemainingDurability,
            double minimumImprovement,
            List<EquippedArmor> equipped,
            List<Candidate> candidates
    ) {
        boolean valid() {
            return sessionKey != Long.MIN_VALUE
                    && tick >= 0L
                    && minimumRemainingDurability >= 0
                    && minimumRemainingDurability <= 1_000_000
                    && Double.isFinite(minimumImprovement)
                    && minimumImprovement >= 0.0
                    && minimumImprovement <= 1_000_000.0
                    && equipped != null
                    && candidates != null;
        }
    }

    public record Decision(
            long sequence,
            long tick,
            Upgrade upgrade,
            BlockReason blockReason
    ) {
        public Decision {
            Objects.requireNonNull(blockReason, "blockReason");
        }

        static Decision equip(
                long sequence,
                long tick,
                Upgrade upgrade
        ) {
            return new Decision(
                    sequence,
                    tick,
                    Objects.requireNonNull(upgrade, "upgrade"),
                    BlockReason.NONE
            );
        }

        static Decision blocked(
                long sequence,
                long tick,
                BlockReason reason
        ) {
            return new Decision(
                    sequence,
                    tick,
                    null,
                    Objects.requireNonNull(reason, "reason")
            );
        }

        static Decision none(
                long sequence,
                long tick,
                BlockReason reason
        ) {
            return blocked(sequence, tick, reason);
        }

        public boolean apply() {
            return upgrade != null && blockReason == BlockReason.NONE;
        }
    }

    public record Timing(
            int actionCooldownTicks,
            int manualYieldTicks
    ) {
        public Timing {
            if (actionCooldownTicks < 1
                    || actionCooldownTicks > 20) {
                throw new IllegalArgumentException(
                        "actionCooldownTicks must be 1..20"
                );
            }
            if (manualYieldTicks < 1 || manualYieldTicks > 10) {
                throw new IllegalArgumentException(
                        "manualYieldTicks must be 1..10"
                );
            }
        }
    }

    public record Snapshot(
            long sessionKey,
            long lastStepTick,
            int cooldownTicks,
            int manualYieldTicks,
            boolean outstandingAction
    ) {
    }
}
