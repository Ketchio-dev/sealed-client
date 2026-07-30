package dev.b2tclient.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrystalScoringTest {
    @Test
    void acceptsDamageMeetingAllSafetyLimits() {
        assertTrue(CrystalScoring.acceptable(8.0, 5.0, 20.0, 6.0, 8.0, false));
    }

    @Test
    void facePlaceOnlyOverridesMinimumTargetDamage() {
        assertFalse(CrystalScoring.acceptable(3.0, 5.0, 20.0, 6.0, 8.0, false));
        assertTrue(CrystalScoring.acceptable(3.0, 5.0, 20.0, 6.0, 8.0, true));
        assertFalse(CrystalScoring.acceptable(3.0, 9.0, 20.0, 6.0, 8.0, true));
    }

    @Test
    void rejectsLethalOrNearLethalSelfDamage() {
        assertFalse(CrystalScoring.acceptable(20.0, 9.6, 10.0, 6.0, 12.0, false));
        assertTrue(CrystalScoring.acceptable(20.0, 9.4, 10.0, 6.0, 12.0, false));
    }

    @Test
    void scoringRewardsTargetDamageAndPenalizesSelfDamageAndDistance() {
        double safe = CrystalScoring.score(10.0, 2.0, 1.0, 2.0, 1.25);
        double risky = CrystalScoring.score(10.0, 6.0, 1.0, 2.0, 1.25);
        double weak = CrystalScoring.score(7.0, 2.0, 1.0, 2.0, 1.25);
        assertTrue(safe > risky);
        assertTrue(safe > weak);
    }

    @Test
    void targetPriorityPrefersCloserAndFacePlaceTargets() {
        double normal = CrystalScoring.targetPriority(4.0, 20.0, 20.0, false);
        double closer = CrystalScoring.targetPriority(2.0, 20.0, 20.0, false);
        double facePlace = CrystalScoring.targetPriority(4.0, 20.0, 20.0, true);
        assertTrue(closer < normal);
        assertTrue(facePlace < normal);
    }
}
