package dev.sealedclient.v26.combat;

import dev.sealedclient.common.combat.CrystalDecisionEngine;

import java.util.List;

/**
 * 26.2 view of the shared crystal scoring engine.
 *
 * <p>The algorithm moved to {@code common} so every platform shares one copy;
 * this keeps the names the 26.2 adapters and tests already use.</p>
 */
final class CrystalDecisionEngine26 {
    private CrystalDecisionEngine26() {
    }

    static long selectBest(
            List<Candidate> candidates,
            Limits limits,
            double localEffectiveHealth
    ) {
        if (candidates == null || limits == null) {
            return -1L;
        }
        List<CrystalDecisionEngine.Candidate> shared =
                candidates.stream().map(Candidate::toShared).toList();
        return CrystalDecisionEngine.selectBest(
                shared, limits.toShared(), localEffectiveHealth
        );
    }

    static boolean safe(
            Candidate candidate,
            Limits limits,
            double localEffectiveHealth
    ) {
        if (limits == null) {
            return false;
        }
        return CrystalDecisionEngine.safe(
                candidate == null ? null : candidate.toShared(),
                limits.toShared(),
                localEffectiveHealth
        );
    }

    static double rawExplosionDamage(
            double distance,
            double exposure,
            double power
    ) {
        return CrystalDecisionEngine.rawExplosionDamage(distance, exposure, power);
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
        CrystalDecisionEngine.Candidate toShared() {
            return new CrystalDecisionEngine.Candidate(
                    key,
                    targetDamage,
                    selfDamage,
                    maximumFriendDamage,
                    friendPresent,
                    lowestFriendEffectiveHealth,
                    distance,
                    valid
            );
        }
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
        CrystalDecisionEngine.Limits toShared() {
            return new CrystalDecisionEngine.Limits(
                    maximumScans,
                    minimumTargetDamage,
                    maximumSelfDamage,
                    maximumFriendDamage,
                    selfSafetyReserve,
                    friendSafetyReserve,
                    selfDamageWeight,
                    distanceWeight
            );
        }
    }
}
