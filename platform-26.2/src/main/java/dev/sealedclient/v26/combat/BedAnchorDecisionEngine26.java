package dev.sealedclient.v26.combat;

import java.util.List;
import java.util.Objects;

/**
 * Pure, deterministic scoring and safety policy for bed/anchor explosions.
 *
 * <p>The dimension decision is deliberately explicit. Respawn anchors are
 * explosive only when the local environment says that anchors cannot set a
 * spawn point; this means a normal Nether anchor is never armed by the aura.
 * Beds are eligible only when the local bed rule explicitly says they
 * explode. Missing or contradictory environment state therefore fails
 * closed.</p>
 */
final class BedAnchorDecisionEngine26 {
    private BedAnchorDecisionEngine26() {
    }

    static boolean dimensionAllowsExplosion(
            ExplosiveKind kind,
            Boolean respawnAnchorWorks,
            Boolean bedExplodes
    ) {
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            case ANCHOR -> Boolean.FALSE.equals(respawnAnchorWorks);
            case BED -> Boolean.TRUE.equals(bedExplodes);
        };
    }

    static long selectBest(
            List<Candidate> candidates,
            Limits limits,
            double localEffectiveHealth
    ) {
        if (limits == null) {
            return -1L;
        }
        return selectBest(
                candidates,
                new Policies(limits, limits),
                localEffectiveHealth
        );
    }

    /**
     * Scores a mixed Anchor/Bed candidate list against the policy belonging
     * to each candidate kind. No field from one module participates in the
     * other module's eligibility or score.
     */
    static long selectBest(
            List<Candidate> candidates,
            Policies policies,
            double localEffectiveHealth
    ) {
        if (candidates == null
                || policies == null
                || !Double.isFinite(localEffectiveHealth)
                || localEffectiveHealth <= 0.0) {
            return -1L;
        }
        long bestKey = -1L;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestTargetDamage = Double.NEGATIVE_INFINITY;
        double bestSelfDamage = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        int bestActionPriority = Integer.MIN_VALUE;
        int anchorScanned = 0;
        int bedScanned = 0;
        for (Candidate candidate : candidates) {
            Limits limits = policies.forKind(
                    candidate == null ? null : candidate.kind()
            );
            if (limits == null) {
                continue;
            }
            // Each kind owns an independent scan budget; a noisy Anchor list
            // cannot consume Bed Aura's allowance (or vice versa).
            if (candidate.kind() == ExplosiveKind.ANCHOR) {
                if (anchorScanned++ >= limits.maximumScans()) {
                    continue;
                }
            } else if (bedScanned++ >= limits.maximumScans()) {
                continue;
            }
            if (localEffectiveHealth <= limits.selfSafetyReserve()) {
                continue;
            }
            if (!safe(candidate, limits, localEffectiveHealth)) {
                continue;
            }
            int actionPriority = candidate.action().priority();
            double score = candidate.targetDamage()
                    - candidate.selfDamage() * limits.selfDamageWeight()
                    - candidate.distance() * limits.distanceWeight()
                    + actionPriority * limits.readyActionWeight();
            if (score > bestScore
                    || (score == bestScore
                    && actionPriority > bestActionPriority)
                    || (score == bestScore
                    && actionPriority == bestActionPriority
                    && candidate.targetDamage() > bestTargetDamage)
                    || (score == bestScore
                    && actionPriority == bestActionPriority
                    && candidate.targetDamage() == bestTargetDamage
                    && candidate.selfDamage() < bestSelfDamage)
                    || (score == bestScore
                    && actionPriority == bestActionPriority
                    && candidate.targetDamage() == bestTargetDamage
                    && candidate.selfDamage() == bestSelfDamage
                    && candidate.distance() < bestDistance)
                    || (score == bestScore
                    && actionPriority == bestActionPriority
                    && candidate.targetDamage() == bestTargetDamage
                    && candidate.selfDamage() == bestSelfDamage
                    && candidate.distance() == bestDistance
                    && (bestKey < 0L || candidate.key() < bestKey))) {
                bestKey = candidate.key();
                bestScore = score;
                bestActionPriority = actionPriority;
                bestTargetDamage = candidate.targetDamage();
                bestSelfDamage = candidate.selfDamage();
                bestDistance = candidate.distance();
            }
        }
        return bestKey;
    }

    static boolean safe(
            Candidate candidate,
            Limits limits,
            double localEffectiveHealth
    ) {
        if (candidate == null
                || candidate.kind() == null
                || candidate.action() == null
                || candidate.key() < 0L
                || !candidate.worldValid()
                || !candidate.resourcesValid()
                || !candidate.dimensionAllowsExplosion()
                || !finiteNonNegative(candidate.targetDamage())
                || !finiteNonNegative(candidate.selfDamage())
                || !finiteNonNegative(candidate.maximumFriendDamage())
                || !finiteNonNegative(candidate.distance())
                || candidate.targetDamage() < limits.minimumTargetDamage()
                || candidate.selfDamage() > limits.maximumSelfDamage()
                || candidate.selfDamage()
                >= localEffectiveHealth - limits.selfSafetyReserve()
                || candidate.maximumFriendDamage()
                > limits.maximumFriendDamage()) {
            return false;
        }
        return !candidate.friendPresent()
                || (Double.isFinite(candidate.lowestFriendEffectiveHealth())
                && candidate.lowestFriendEffectiveHealth()
                > limits.friendSafetyReserve()
                && candidate.maximumFriendDamage()
                < candidate.lowestFriendEffectiveHealth()
                - limits.friendSafetyReserve());
    }

    static boolean safe(
            Candidate candidate,
            Policies policies,
            double localEffectiveHealth
    ) {
        if (candidate == null || policies == null) {
            return false;
        }
        Limits limits = policies.forKind(candidate.kind());
        return limits != null
                && localEffectiveHealth > limits.selfSafetyReserve()
                && safe(candidate, limits, localEffectiveHealth);
    }

    /**
     * Vanilla exposure curve used by both bad-respawn-point explosions.
     * Production supplies full exposure for self/friends and at most eight
     * sampled rays for the target.
     */
    static double rawExplosionDamage(
            double distance,
            double exposure,
            double power
    ) {
        if (!Double.isFinite(distance)
                || !Double.isFinite(exposure)
                || !Double.isFinite(power)
                || distance < 0.0
                || power <= 0.0
                || exposure <= 0.0
                || distance >= power * 2.0) {
            return 0.0;
        }
        double clampedExposure = Math.min(1.0, exposure);
        double impact = (1.0 - distance / (power * 2.0))
                * clampedExposure;
        return impact <= 0.0
                ? 0.0
                : ((impact * impact + impact)
                * 0.5 * 7.0 * power * 2.0) + 1.0;
    }

    /**
     * Restores a temporary slot only if it still owns the selection.
     */
    static int restorationSlot(
            int previousSlot,
            int appliedSlot,
            int currentSlot
    ) {
        return previousSlot >= 0
                && previousSlot < 9
                && appliedSlot >= 0
                && appliedSlot < 9
                && currentSlot == appliedSlot
                ? previousSlot
                : -1;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    enum ExplosiveKind {
        ANCHOR,
        BED
    }

    enum Action {
        USE(3),
        CHARGE(2),
        PLACE(1);

        private final int priority;

        Action(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }
    }

    record Candidate(
            long key,
            ExplosiveKind kind,
            Action action,
            double targetDamage,
            double selfDamage,
            double maximumFriendDamage,
            boolean friendPresent,
            double lowestFriendEffectiveHealth,
            double distance,
            boolean dimensionAllowsExplosion,
            boolean resourcesValid,
            boolean worldValid
    ) {
    }

    record Limits(
            int maximumScans,
            double minimumTargetDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfSafetyReserve,
            double friendSafetyReserve,
            double selfDamageWeight,
            double distanceWeight,
            double readyActionWeight
    ) {
        Limits {
            if (maximumScans <= 0
                    || !finiteNonNegative(minimumTargetDamage)
                    || !finiteNonNegative(maximumSelfDamage)
                    || !finiteNonNegative(maximumFriendDamage)
                    || !finiteNonNegative(selfSafetyReserve)
                    || !finiteNonNegative(friendSafetyReserve)
                    || !finiteNonNegative(selfDamageWeight)
                    || !finiteNonNegative(distanceWeight)
                    || !finiteNonNegative(readyActionWeight)) {
                throw new IllegalArgumentException(
                        "Invalid bed/anchor scoring limits"
                );
            }
        }
    }

    record Policies(Limits anchor, Limits bed) {
        Policies {
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(bed, "bed");
        }

        Limits forKind(ExplosiveKind kind) {
            if (kind == null) {
                return null;
            }
            return kind == ExplosiveKind.ANCHOR ? anchor : bed;
        }
    }
}
