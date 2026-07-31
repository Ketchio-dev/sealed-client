package dev.sealedclient.common.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrystalDecisionEngineTest {
    private static final CrystalDecisionEngine.Limits LIMITS =
            new CrystalDecisionEngine.Limits(
                    64, 2.0, 8.0, 4.0, 1.0, 4.0, 1.5, 0.05
            );

    @Test
    void coveredTargetsStillTakeTheMinimumBlastDamage() {
        // This is the defect the move exposed. The 26.2 copy of the curve
        // returned zero once every sample ray was blocked, so a target behind
        // obsidian looked untouchable. A real detonation on a dedicated server
        // dealt 1.0 raw at this distance with exposure 0.
        assertEquals(
                1.0,
                CrystalDecisionEngine.rawExplosionDamage(3.1623, 0.0, 6.0),
                1.0e-9,
                "a fully covered target inside the radius still takes one point"
        );
    }

    @Test
    void theCurveAgreesWithTheMeasuredFormula() {
        // Two implementations of one curve is how the above defect survived.
        // This fails if they are ever allowed to diverge again.
        double[][] cases = {
                {1.4142, 1.0}, {2.2361, 1.0}, {4.1231, 1.0},
                {7.0711, 1.0}, {3.1623, 0.0}, {3.1623, 0.5},
        };
        for (double[] probe : cases) {
            assertEquals(
                    ExplosionDamageFormula.rawDamage(probe[0], probe[1], 6.0),
                    CrystalDecisionEngine.rawExplosionDamage(probe[0], probe[1], 6.0),
                    1.0e-9,
                    "distance " + probe[0] + " exposure " + probe[1]
            );
        }
    }

    @Test
    void outsideTheRadiusNothingIsDealt() {
        assertEquals(0.0, CrystalDecisionEngine.rawExplosionDamage(12.0, 1.0, 6.0), 1.0e-9);
        assertEquals(0.0, CrystalDecisionEngine.rawExplosionDamage(99.0, 1.0, 6.0), 1.0e-9);
    }

    @Test
    void nonsenseInputsAreRejectedRatherThanPropagated() {
        assertEquals(0.0, CrystalDecisionEngine.rawExplosionDamage(Double.NaN, 1.0, 6.0));
        assertEquals(0.0, CrystalDecisionEngine.rawExplosionDamage(1.0, Double.NaN, 6.0));
        assertEquals(0.0, CrystalDecisionEngine.rawExplosionDamage(-1.0, 1.0, 6.0));
        assertEquals(0.0, CrystalDecisionEngine.rawExplosionDamage(1.0, 1.0, 0.0));
    }

    @Test
    void exposureAboveOneCannotAmplifyDamage() {
        assertEquals(
                CrystalDecisionEngine.rawExplosionDamage(2.0, 1.0, 6.0),
                CrystalDecisionEngine.rawExplosionDamage(2.0, 9.0, 6.0),
                1.0e-9
        );
    }

    @Test
    void theHighestScoringSafeCandidateWins() {
        long best = CrystalDecisionEngine.selectBest(
                List.of(
                        candidate(1L, 6.0, 1.0, 2.0),
                        candidate(2L, 12.0, 1.0, 2.0),
                        candidate(3L, 9.0, 1.0, 2.0)
                ),
                LIMITS,
                20.0
        );
        assertEquals(2L, best);
    }

    @Test
    void candidateOrderDoesNotChangeTheChoice() {
        List<CrystalDecisionEngine.Candidate> candidates = new ArrayList<>(List.of(
                candidate(1L, 6.0, 1.0, 2.0),
                candidate(2L, 12.0, 1.0, 2.0),
                candidate(3L, 12.0, 1.0, 2.0),
                candidate(4L, 9.0, 1.0, 2.0)
        ));
        long expected = CrystalDecisionEngine.selectBest(candidates, LIMITS, 20.0);
        Random random = new Random(20260101L);
        for (int trial = 0; trial < 200; trial++) {
            Collections.shuffle(candidates, random);
            assertEquals(
                    expected,
                    CrystalDecisionEngine.selectBest(candidates, LIMITS, 20.0),
                    "selection must not depend on candidate order"
            );
        }
    }

    @Test
    void aPlacementThatWouldKillTheUserIsNeverChosen() {
        long best = CrystalDecisionEngine.selectBest(
                List.of(candidate(1L, 40.0, 7.5, 2.0)),
                LIMITS,
                8.0
        );
        assertEquals(-1L, best, "self damage at or above health must be refused");
    }

    @Test
    void aPlacementThatWouldKillAFriendIsNeverChosen() {
        CrystalDecisionEngine.Candidate lethalToFriend =
                new CrystalDecisionEngine.Candidate(
                        1L, 40.0, 1.0, 3.5, true, 5.0, 2.0, true
                );
        assertFalse(CrystalDecisionEngine.safe(lethalToFriend, LIMITS, 20.0));
        assertEquals(
                -1L,
                CrystalDecisionEngine.selectBest(List.of(lethalToFriend), LIMITS, 20.0)
        );
    }

    @Test
    void scanningStopsAtTheConfiguredLimit() {
        CrystalDecisionEngine.Limits twoScans = new CrystalDecisionEngine.Limits(
                2, 2.0, 8.0, 4.0, 1.0, 4.0, 1.5, 0.05
        );
        long best = CrystalDecisionEngine.selectBest(
                List.of(
                        candidate(1L, 6.0, 1.0, 2.0),
                        candidate(2L, 7.0, 1.0, 2.0),
                        candidate(3L, 99.0, 1.0, 2.0)
                ),
                twoScans,
                20.0
        );
        assertEquals(2L, best, "the third candidate is past the scan budget");
    }

    @Test
    void nullAndEmptyInputsSelectNothing() {
        assertEquals(-1L, CrystalDecisionEngine.selectBest(null, LIMITS, 20.0));
        assertEquals(-1L, CrystalDecisionEngine.selectBest(List.of(), LIMITS, 20.0));
        assertEquals(-1L, CrystalDecisionEngine.selectBest(
                List.of(candidate(1L, 6.0, 1.0, 2.0)), null, 20.0));
    }

    @Test
    void invalidLimitsAreRejectedAtConstruction() {
        assertTrue(
                throwsIllegalArgument(() -> new CrystalDecisionEngine.Limits(
                        0, 2.0, 8.0, 4.0, 1.0, 4.0, 1.5, 0.05)),
                "a zero scan budget is not a usable configuration"
        );
        assertTrue(
                throwsIllegalArgument(() -> new CrystalDecisionEngine.Limits(
                        64, Double.NaN, 8.0, 4.0, 1.0, 4.0, 1.5, 0.05))
        );
    }

    private static boolean throwsIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static CrystalDecisionEngine.Candidate candidate(
            long key,
            double targetDamage,
            double selfDamage,
            double distance
    ) {
        return new CrystalDecisionEngine.Candidate(
                key, targetDamage, selfDamage, 0.0, false, 20.0, distance, true
        );
    }
}
