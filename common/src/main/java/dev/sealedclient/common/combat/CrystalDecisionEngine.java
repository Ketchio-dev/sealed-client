package dev.sealedclient.common.combat;

import java.util.List;

/**
 * Pure, deterministic crystal scoring shared by every platform adapter.
 *
 * <p>Selection is total and order-independent: candidates are compared on
 * score, then target damage, then self damage, then distance, then key, so two
 * runs over the same set in a different order pick the same placement.</p>
 */
public final class CrystalDecisionEngine {
    private CrystalDecisionEngine() {
    }

    /**
     * Returns the key of the best safe candidate, or {@code -1} if none
     * qualifies.
     */
    public static long selectBest(
            List<Candidate> candidates,
            Limits limits,
            double localEffectiveHealth
    ) {
        if (candidates == null
                || limits == null
                || !Double.isFinite(localEffectiveHealth)
                || localEffectiveHealth <= limits.selfSafetyReserve()) {
            return -1L;
        }
        long bestKey = -1L;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestTargetDamage = Double.NEGATIVE_INFINITY;
        double bestSelfDamage = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        int scanned = 0;
        for (Candidate candidate : candidates) {
            if (scanned++ >= limits.maximumScans()) {
                break;
            }
            if (!safe(candidate, limits, localEffectiveHealth)) {
                continue;
            }
            double score = candidate.targetDamage()
                    - candidate.selfDamage() * limits.selfDamageWeight()
                    - candidate.distance() * limits.distanceWeight();
            if (score > bestScore
                    || (score == bestScore
                    && candidate.targetDamage() > bestTargetDamage)
                    || (score == bestScore
                    && candidate.targetDamage() == bestTargetDamage
                    && candidate.selfDamage() < bestSelfDamage)
                    || (score == bestScore
                    && candidate.targetDamage() == bestTargetDamage
                    && candidate.selfDamage() == bestSelfDamage
                    && candidate.distance() < bestDistance)
                    || (score == bestScore
                    && candidate.targetDamage() == bestTargetDamage
                    && candidate.selfDamage() == bestSelfDamage
                    && candidate.distance() == bestDistance
                    && (bestKey < 0L || candidate.key() < bestKey))) {
                bestKey = candidate.key();
                bestScore = score;
                bestTargetDamage = candidate.targetDamage();
                bestSelfDamage = candidate.selfDamage();
                bestDistance = candidate.distance();
            }
        }
        return bestKey;
    }

    /** Whether a candidate passes every self, friend and sanity limit. */
    public static boolean safe(
            Candidate candidate,
            Limits limits,
            double localEffectiveHealth
    ) {
        if (candidate == null
                || !candidate.valid()
                || candidate.key() < 0L
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

    /**
     * Vanilla's explosion curve, delegating to the formula that was verified
     * against real detonations on a dedicated server.
     *
     * <p>This used to be a second, independent copy of the curve. It disagreed
     * with the measured one for fully covered targets, predicting no damage
     * where the server deals one point, which made cover look safe when it was
     * not. Sharing the measured implementation is the point of this method
     * existing at all.</p>
     */
    public static double rawExplosionDamage(
            double distance,
            double exposure,
            double power
    ) {
        if (!Double.isFinite(distance)
                || !Double.isFinite(exposure)
                || !Double.isFinite(power)
                || distance < 0.0
                || power <= 0.0
                || exposure < 0.0) {
            return 0.0;
        }
        return ExplosionDamageFormula.rawDamage(
                distance, Math.min(1.0, exposure), power
        );
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    /** One placement under consideration. */
    public record Candidate(
            long key,
            double targetDamage,
            double selfDamage,
            double maximumFriendDamage,
            boolean friendPresent,
            double lowestFriendEffectiveHealth,
            double distance,
            boolean valid
    ) {
    }

    /** Tunable bounds applied to every candidate. */
    public record Limits(
            int maximumScans,
            double minimumTargetDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfSafetyReserve,
            double friendSafetyReserve,
            double selfDamageWeight,
            double distanceWeight
    ) {
        public Limits {
            if (maximumScans <= 0
                    || !finiteNonNegative(minimumTargetDamage)
                    || !finiteNonNegative(maximumSelfDamage)
                    || !finiteNonNegative(maximumFriendDamage)
                    || !finiteNonNegative(selfSafetyReserve)
                    || !finiteNonNegative(friendSafetyReserve)
                    || !finiteNonNegative(selfDamageWeight)
                    || !finiteNonNegative(distanceWeight)) {
                throw new IllegalArgumentException("Invalid crystal scoring limits");
            }
        }
    }
}
