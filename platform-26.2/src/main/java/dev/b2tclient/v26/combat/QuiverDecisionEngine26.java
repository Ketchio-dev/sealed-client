package dev.b2tclient.v26.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure tipped-arrow safety, usefulness and confirmation decisions for Quiver.
 */
public final class QuiverDecisionEngine26 {
    private QuiverDecisionEngine26() {
    }

    public static Optional<ArrowDecision> select(
            List<ArrowCandidate> candidates,
            int minimumEffectRemainingTicks,
            double missingHealth
    ) {
        if (candidates == null
                || minimumEffectRemainingTicks < 0
                || !Double.isFinite(missingHealth)
                || missingHealth < 0.0) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .map(candidate -> evaluate(
                        candidate,
                        minimumEffectRemainingTicks,
                        missingHealth
                ))
                .filter(ArrowDecision::safeAndUseful)
                .sorted(Comparator
                        .comparingInt(ArrowDecision::utilityScore)
                        .reversed()
                        .thenComparingInt(decision ->
                                decision.candidate().inventorySlot()))
                .findFirst();
    }

    public static ArrowDecision evaluate(
            ArrowCandidate candidate,
            int minimumEffectRemainingTicks,
            double missingHealth
    ) {
        if (candidate == null
                || candidate.inventorySlot() < 0
                || candidate.count() <= 0
                || candidate.effects() == null
                || candidate.effects().isEmpty()
                || minimumEffectRemainingTicks < 0
                || !Double.isFinite(missingHealth)
                || missingHealth < 0.0) {
            return ArrowDecision.blocked(candidate, BlockReason.INVALID);
        }
        for (EffectCandidate effect : candidate.effects()) {
            if (effect == null || !effect.valid()) {
                return ArrowDecision.blocked(
                        candidate,
                        BlockReason.INVALID
                );
            }
            if (!effect.beneficial()) {
                return ArrowDecision.blocked(
                        candidate,
                        BlockReason.HARMFUL_EFFECT
                );
            }
            if (effect.instantaneous() && !effect.healthRestoring()) {
                return ArrowDecision.blocked(
                        candidate,
                        BlockReason.UNCONFIRMABLE_INSTANT_EFFECT
                );
            }
        }

        List<String> useful = candidate.effects().stream()
                .filter(effect -> useful(
                        effect,
                        minimumEffectRemainingTicks,
                        missingHealth
                ))
                .map(EffectCandidate::effectKey)
                .sorted()
                .toList();
        if (useful.isEmpty()) {
            return ArrowDecision.blocked(
                    candidate,
                    BlockReason.REDUNDANT_EFFECT
            );
        }

        int score = candidate.effects().stream()
                .filter(effect -> useful.contains(effect.effectKey()))
                .mapToInt(effect -> {
                    if (effect.healthRestoring()) {
                        return 100_000
                                + (int) Math.min(
                                        10_000,
                                        Math.round(missingHealth * 100.0)
                                );
                    }
                    int amplifierGain = Math.max(
                            0,
                            effect.amplifier()
                                    - effect.currentAmplifier()
                    );
                    int durationGain = Math.max(
                            0,
                            effect.appliedDurationTicks()
                                    - effect.currentRemainingTicks()
                    );
                    return 10_000
                            + amplifierGain * 1_000
                            + Math.min(durationGain, 9_999);
                })
                .sum();
        return new ArrowDecision(
                candidate,
                BlockReason.NONE,
                useful,
                score
        );
    }

    static boolean useful(
            EffectCandidate effect,
            int minimumEffectRemainingTicks,
            double missingHealth
    ) {
        if (effect.healthRestoring()) {
            return missingHealth >= 1.0;
        }
        if (effect.instantaneous()) {
            return false;
        }
        if (effect.currentAmplifier() < effect.amplifier()) {
            return true;
        }
        return effect.currentAmplifier() == effect.amplifier()
                && effect.currentRemainingTicks()
                < minimumEffectRemainingTicks
                && effect.appliedDurationTicks()
                > effect.currentRemainingTicks();
    }

    /**
     * A release is accepted only after the client inventory reflects either
     * ammo consumption or bow durability consumption.
     */
    public static boolean shotAccepted(
            int arrowCountBefore,
            int arrowCountNow,
            int bowDamageBefore,
            int bowDamageNow
    ) {
        if (arrowCountBefore < 0
                || arrowCountNow < 0
                || bowDamageBefore < 0
                || bowDamageNow < 0) {
            return false;
        }
        return arrowCountNow < arrowCountBefore
                || bowDamageNow > bowDamageBefore;
    }

    /**
     * Strong confirmation requires a server-reflected duration/amplifier
     * improvement, or an effective-health increase for Instant Health.
     */
    public static boolean effectConfirmed(
            List<EffectObservation> observations,
            double effectiveHealthBefore,
            double effectiveHealthNow
    ) {
        if (observations == null
                || !Double.isFinite(effectiveHealthBefore)
                || !Double.isFinite(effectiveHealthNow)) {
            return false;
        }
        for (EffectObservation effect : observations) {
            if (effect == null || !effect.valid()) {
                continue;
            }
            if (effect.healthRestoring()
                    && effectiveHealthNow > effectiveHealthBefore + 0.25) {
                return true;
            }
            if (!effect.instantaneous()
                    && (effect.currentAmplifier()
                    > effect.beforeAmplifier()
                    || (effect.currentAmplifier()
                    == effect.beforeAmplifier()
                    && effect.currentRemainingTicks()
                    > effect.beforeRemainingTicks() + 2))) {
                return true;
            }
        }
        return false;
    }

    public enum BlockReason {
        NONE,
        INVALID,
        HARMFUL_EFFECT,
        UNCONFIRMABLE_INSTANT_EFFECT,
        REDUNDANT_EFFECT
    }

    public record ArrowCandidate(
            int inventorySlot,
            int count,
            List<EffectCandidate> effects
    ) {
        public ArrowCandidate {
            effects = effects == null ? null : List.copyOf(effects);
        }
    }

    public record EffectCandidate(
            String effectKey,
            boolean beneficial,
            boolean instantaneous,
            boolean healthRestoring,
            int amplifier,
            int appliedDurationTicks,
            int currentAmplifier,
            int currentRemainingTicks
    ) {
        boolean valid() {
            return effectKey != null
                    && !effectKey.isBlank()
                    && amplifier >= 0
                    && amplifier <= 255
                    && appliedDurationTicks >= 0
                    && currentAmplifier >= -1
                    && currentAmplifier <= 255
                    && currentRemainingTicks >= 0
                    && (!healthRestoring
                    || (beneficial && instantaneous));
        }
    }

    public record ArrowDecision(
            ArrowCandidate candidate,
            BlockReason blockReason,
            List<String> usefulEffectKeys,
            int utilityScore
    ) {
        public ArrowDecision {
            blockReason = Objects.requireNonNull(
                    blockReason,
                    "blockReason"
            );
            usefulEffectKeys = usefulEffectKeys == null
                    ? List.of()
                    : List.copyOf(usefulEffectKeys);
        }

        public boolean safeAndUseful() {
            return candidate != null
                    && blockReason == BlockReason.NONE
                    && !usefulEffectKeys.isEmpty()
                    && utilityScore > 0;
        }

        static ArrowDecision blocked(
                ArrowCandidate candidate,
                BlockReason reason
        ) {
            return new ArrowDecision(
                    candidate,
                    reason,
                    List.of(),
                    0
            );
        }
    }

    public record EffectObservation(
            String effectKey,
            boolean instantaneous,
            boolean healthRestoring,
            int beforeAmplifier,
            int beforeRemainingTicks,
            int currentAmplifier,
            int currentRemainingTicks
    ) {
        boolean valid() {
            return effectKey != null
                    && !effectKey.isBlank()
                    && beforeAmplifier >= -1
                    && currentAmplifier >= -1
                    && beforeRemainingTicks >= 0
                    && currentRemainingTicks >= 0;
        }
    }
}
