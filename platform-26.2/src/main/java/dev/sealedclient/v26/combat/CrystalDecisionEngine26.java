package dev.sealedclient.v26.combat;

import java.util.List;

/**
 * Pure, deterministic crystal scoring shared by the live 26.2 adapter and
 * unit tests.
 */
final class CrystalDecisionEngine26 {
    private CrystalDecisionEngine26() {
    }

    static long selectBest(
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

    static boolean safe(
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
     * Vanilla's exposure-free explosion curve. Production uses this with full
     * exposure for fail-safe self/friend checks and sampled exposure for the
     * intended target.
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

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    record Candidate(
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

    record Limits(
            int maximumScans,
            double minimumTargetDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfSafetyReserve,
            double friendSafetyReserve,
            double selfDamageWeight,
            double distanceWeight
    ) {
        Limits {
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
