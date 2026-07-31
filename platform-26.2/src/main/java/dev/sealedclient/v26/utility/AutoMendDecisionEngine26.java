package dev.sealedclient.v26.utility;

import dev.sealedclient.common.item.DurabilityPercent;

import java.util.List;
import java.util.Objects;

/**
 * Pure state machine for conservative XP-bottle armor mending.
 *
 * <p>A successful use is not treated as a successful mend. The engine waits
 * for a later observation where the same tracked armor set has at least one
 * piece whose actual damage decreased. A timeout permits a bounded retry but
 * never invents durability progress.</p>
 */
public final class AutoMendDecisionEngine26 {
    private Configuration configuration;
    private Object sessionIdentity;
    private boolean mending;
    private boolean awaitingRepair;
    private int cooldownTicks;
    private int confirmationAge;
    private int baselineDamage;
    private int baselineBottleCount;
    private List<ArmorPiece> baselineArmor = List.of();
    private int confirmedRepairEvents;
    private Decision lastDecision = Decision.none(BlockReason.INACTIVE);

    public AutoMendDecisionEngine26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    public Decision step(Observation observation) {
        Observation current = Objects.requireNonNull(
                observation,
                "observation"
        );
        if (current.sessionIdentity() != sessionIdentity) {
            reset();
            sessionIdentity = current.sessionIdentity();
        }
        if (!current.enabled()) {
            resetState();
            return remember(Decision.none(BlockReason.DISABLED));
        }
        List<ArmorPiece> armor = current.armor();
        int totalDamage = totalDamage(armor);
        if (awaitingRepair) {
            if (!sameEquipment(baselineArmor, armor)) {
                awaitingRepair = false;
                confirmationAge = 0;
                baselineArmor = List.of();
                baselineDamage = 0;
                baselineBottleCount = 0;
                mending = false;
                cooldownTicks = configuration.delayTicks();
                return remember(Decision.none(BlockReason.ARMOR_CHANGED));
            }
            if (hasObservedRepair(baselineArmor, armor)) {
                awaitingRepair = false;
                confirmationAge = 0;
                baselineArmor = List.of();
                baselineDamage = 0;
                baselineBottleCount = 0;
                confirmedRepairEvents++;
            } else if (!usable(current)) {
                return remember(Decision.hold(
                        current.bottleSlot(),
                        current.bottleCount(),
                        totalDamage,
                        armor,
                        BlockReason.AWAITING_DURABILITY
                ));
            } else {
                confirmationAge++;
                if (confirmationAge
                        <= configuration.confirmationTimeoutTicks()) {
                    return remember(Decision.hold(
                            current.bottleSlot(),
                            current.bottleCount(),
                            totalDamage,
                            armor,
                            BlockReason.AWAITING_DURABILITY
                    ));
                }
                awaitingRepair = false;
                confirmationAge = 0;
                baselineArmor = List.of();
                cooldownTicks = Math.max(
                        cooldownTicks,
                        configuration.delayTicks()
                );
            }
        }
        if (!usable(current)) {
            resetState();
            return remember(Decision.none(blockReason(current)));
        }

        if (armor.isEmpty()) {
            resetState();
            return remember(Decision.none(BlockReason.NO_MENDING_ARMOR));
        }
        int lowest = lowestRemainingPercent(armor);
        if (!mending && lowest > configuration.startAtPercent()) {
            return remember(Decision.none(BlockReason.ABOVE_START));
        }
        if (mending
                && allAtOrAbove(armor, configuration.stopAtPercent())) {
            resetState();
            return remember(Decision.none(BlockReason.REPAIRED));
        }
        if (current.bottleSlot() < 0 || current.bottleCount() < 1) {
            resetState();
            return remember(Decision.none(BlockReason.NO_BOTTLES));
        }

        mending = true;
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (cooldownTicks > 0) {
            return remember(Decision.hold(
                    current.bottleSlot(),
                    current.bottleCount(),
                    totalDamage,
                    armor,
                    BlockReason.COOLDOWN
            ));
        }
        return remember(Decision.throwBottle(
                current.bottleSlot(),
                current.bottleCount(),
                totalDamage,
                armor
        ));
    }

    /**
     * Commits only the exact most recently emitted throw decision.
     */
    public void commit(Decision decision, boolean applied) {
        if (decision == null
                || decision != lastDecision
                || decision.action() != Action.THROW) {
            return;
        }
        if (!applied) {
            return;
        }
        baselineDamage = decision.totalArmorDamage();
        baselineBottleCount = decision.bottleCount();
        baselineArmor = decision.armor();
        awaitingRepair = true;
        confirmationAge = 0;
        cooldownTicks = configuration.delayTicks();
    }

    public void reset() {
        sessionIdentity = null;
        resetState();
        confirmedRepairEvents = 0;
        lastDecision = Decision.none(BlockReason.INACTIVE);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                mending,
                awaitingRepair,
                cooldownTicks,
                confirmationAge,
                baselineDamage,
                baselineBottleCount,
                baselineArmor,
                confirmedRepairEvents
        );
    }

    static int lowestRemainingPercent(List<ArmorPiece> armor) {
        Objects.requireNonNull(armor, "armor");
        return armor.stream()
                .mapToInt(ArmorPiece::remainingPercent)
                .min()
                .orElse(100);
    }

    static int totalDamage(List<ArmorPiece> armor) {
        Objects.requireNonNull(armor, "armor");
        long total = armor.stream()
                .mapToLong(ArmorPiece::damage)
                .sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    static boolean sameEquipment(
            List<ArmorPiece> baseline,
            List<ArmorPiece> current
    ) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(current, "current");
        if (baseline.size() != current.size()) {
            return false;
        }
        for (int index = 0; index < baseline.size(); index++) {
            ArmorPiece before = baseline.get(index);
            ArmorPiece after = current.get(index);
            if (!before.sameEquipment(after)) {
                return false;
            }
        }
        return true;
    }

    static boolean hasObservedRepair(
            List<ArmorPiece> baseline,
            List<ArmorPiece> current
    ) {
        if (!sameEquipment(baseline, current)) {
            return false;
        }
        for (int index = 0; index < baseline.size(); index++) {
            if (current.get(index).damage()
                    < baseline.get(index).damage()) {
                return true;
            }
        }
        return false;
    }

    private boolean usable(Observation observation) {
        return observation.enabled()
                && observation.sessionIdentity() != null
                && observation.sessionReady()
                && observation.safetyReady()
                && observation.screenClear()
                && observation.playerAlive()
                && (!configuration.requireSneak()
                || observation.sneaking());
    }

    private BlockReason blockReason(Observation observation) {
        if (!observation.enabled()) {
            return BlockReason.DISABLED;
        }
        if (observation.sessionIdentity() == null
                || !observation.sessionReady()) {
            return BlockReason.NO_SESSION;
        }
        if (!observation.playerAlive()) {
            return BlockReason.PLAYER_DEAD;
        }
        if (!observation.safetyReady()) {
            return BlockReason.SAFETY;
        }
        if (!observation.screenClear()) {
            return BlockReason.SCREEN_OPEN;
        }
        if (configuration.requireSneak() && !observation.sneaking()) {
            return BlockReason.SNEAK_REQUIRED;
        }
        return BlockReason.INACTIVE;
    }

    private static boolean allAtOrAbove(
            List<ArmorPiece> armor,
            int percent
    ) {
        return armor.stream().allMatch(
                piece -> piece.remainingPercent() >= percent
        );
    }

    private void resetState() {
        mending = false;
        awaitingRepair = false;
        cooldownTicks = 0;
        confirmationAge = 0;
        baselineDamage = 0;
        baselineBottleCount = 0;
        baselineArmor = List.of();
    }

    private Decision remember(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    public enum Action {
        NONE,
        HOLD,
        THROW
    }

    public enum BlockReason {
        READY,
        INACTIVE,
        DISABLED,
        NO_SESSION,
        PLAYER_DEAD,
        SAFETY,
        SCREEN_OPEN,
        SNEAK_REQUIRED,
        NO_MENDING_ARMOR,
        ABOVE_START,
        REPAIRED,
        NO_BOTTLES,
        ARMOR_CHANGED,
        COOLDOWN,
        AWAITING_DURABILITY
    }

    public record Configuration(
            int startAtPercent,
            int stopAtPercent,
            int delayTicks,
            boolean requireSneak,
            int confirmationTimeoutTicks
    ) {
        public Configuration {
            if (startAtPercent < 5 || startAtPercent > 95) {
                throw new IllegalArgumentException(
                        "Start durability must be in [5, 95]"
                );
            }
            if (stopAtPercent < 10 || stopAtPercent > 100) {
                throw new IllegalArgumentException(
                        "Stop durability must be in [10, 100]"
                );
            }
            stopAtPercent = Math.max(startAtPercent, stopAtPercent);
            if (delayTicks < 1 || delayTicks > 20) {
                throw new IllegalArgumentException(
                        "Mend delay must be in [1, 20]"
                );
            }
            if (confirmationTimeoutTicks < 4
                    || confirmationTimeoutTicks > 100) {
                throw new IllegalArgumentException(
                        "Mend confirmation timeout must be in [4, 100]"
                );
            }
        }
    }

    public record ArmorPiece(
            String slot,
            String itemToken,
            int damage,
            int maximumDamage
    ) {
        public ArmorPiece {
            if (slot == null || slot.isBlank()) {
                throw new IllegalArgumentException(
                        "Armor slot cannot be blank"
                );
            }
            if (itemToken == null || itemToken.isBlank()) {
                throw new IllegalArgumentException(
                        "Armor item token cannot be blank"
                );
            }
            if (maximumDamage < 1
                    || damage < 0
                    || damage > maximumDamage) {
                throw new IllegalArgumentException(
                        "Armor damage must be in [0, maximumDamage]"
                );
            }
        }

        public int remainingPercent() {
            return DurabilityPercent.fromDamage(damage, maximumDamage);
        }

        boolean sameEquipment(ArmorPiece other) {
            return other != null
                    && slot.equals(other.slot)
                    && itemToken.equals(other.itemToken)
                    && maximumDamage == other.maximumDamage;
        }
    }

    public record Observation(
            Object sessionIdentity,
            boolean enabled,
            boolean sessionReady,
            boolean safetyReady,
            boolean screenClear,
            boolean playerAlive,
            boolean sneaking,
            int selectedSlot,
            float pitch,
            int bottleSlot,
            int bottleCount,
            List<ArmorPiece> armor
    ) {
        public Observation {
            if (selectedSlot < -1 || selectedSlot > 8) {
                throw new IllegalArgumentException(
                        "Selected slot must be in [-1, 8]"
                );
            }
            if (bottleSlot < -1 || bottleSlot > 8) {
                throw new IllegalArgumentException(
                        "Bottle slot must be in [-1, 8]"
                );
            }
            if (bottleCount < 0) {
                throw new IllegalArgumentException(
                        "Bottle count cannot be negative"
                );
            }
            if (!Float.isFinite(pitch)) {
                throw new IllegalArgumentException(
                        "Pitch must be finite"
                );
            }
            armor = List.copyOf(Objects.requireNonNull(armor, "armor"));
        }
    }

    public record Decision(
            Action action,
            int bottleSlot,
            int bottleCount,
            int totalArmorDamage,
            List<ArmorPiece> armor,
            BlockReason blockReason
    ) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            armor = List.copyOf(Objects.requireNonNull(armor, "armor"));
            blockReason = Objects.requireNonNull(
                    blockReason,
                    "blockReason"
            );
        }

        public boolean requiresOwnership() {
            return action == Action.THROW;
        }

        static Decision none(BlockReason reason) {
            return new Decision(
                    Action.NONE,
                    -1,
                    0,
                    0,
                    List.of(),
                    reason
            );
        }

        static Decision hold(
                int slot,
                int count,
                int damage,
                List<ArmorPiece> armor,
                BlockReason reason
        ) {
            return new Decision(
                    Action.HOLD,
                    slot,
                    count,
                    damage,
                    armor,
                    reason
            );
        }

        static Decision throwBottle(
                int slot,
                int count,
                int damage,
                List<ArmorPiece> armor
        ) {
            return new Decision(
                    Action.THROW,
                    slot,
                    count,
                    damage,
                    armor,
                    BlockReason.READY
            );
        }
    }

    public record Snapshot(
            boolean mending,
            boolean awaitingRepair,
            int cooldownTicks,
            int confirmationAge,
            int baselineDamage,
            int baselineBottleCount,
            List<ArmorPiece> baselineArmor,
            int confirmedRepairEvents
    ) {
        public Snapshot {
            baselineArmor = List.copyOf(
                    Objects.requireNonNull(baselineArmor, "baselineArmor")
            );
        }
    }
}
