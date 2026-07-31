package dev.sealedclient.combat;

import dev.sealedclient.common.combat.CrystalDecisionEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records where 1.21.4 crystal scoring differs from the shared engine.
 *
 * <p>The two were written independently and do not agree. Moving 1.21.4 onto
 * the shared engine would change which placement it picks in a real fight, so
 * the divergence is pinned here instead of being quietly removed. If the
 * platforms are ever deliberately unified, these tests are what has to change,
 * and that makes the decision visible in a diff.</p>
 */
final class CrystalScoringDivergenceTest {
    @Test
    void theTwoPlatformsWeighDistanceDifferently() {
        // 1.21.4 charges for two distances, blast-to-target and player-to-block,
        // at fixed weights. The shared engine charges once, at a configurable
        // weight. Same inputs, different answers.
        double local = CrystalScoring.score(10.0, 2.0, 3.0, 4.0, 1.5);
        double shared = 10.0 - 2.0 * 1.5 - 3.0 * 0.05;
        assertNotEquals(
                shared,
                local,
                1.0e-9,
                "if these ever agree, the platforms were unified and this test should say so"
        );
    }

    @Test
    void localScoringPenalisesReachAsWellAsBlastDistance() {
        double near = CrystalScoring.score(10.0, 2.0, 3.0, 1.0, 1.5);
        double far = CrystalScoring.score(10.0, 2.0, 3.0, 6.0, 1.5);
        assertTrue(near > far, "a placement further from the player scores lower");
    }

    @Test
    void theSelfDamageMarginIsFixedLocallyAndConfigurableInTheSharedEngine() {
        // 1.21.4 always keeps half a heart in reserve; the margin is written
        // into the comparison and cannot be turned off. The shared engine takes
        // the reserve as a parameter, so the same placement is refused or
        // allowed depending on configuration. Anyone unifying these has to
        // choose a default, and this records what that choice is worth: at
        // reserve zero the shared engine will leave the user on 0.4 health.
        assertFalse(
                CrystalScoring.acceptable(40.0, 9.6, 10.0, 6.0, 12.0, false),
                "1.21.4 refuses a placement that lands within half a heart"
        );
        assertEquals(
                1L,
                selectWithReserve(0.0),
                "the shared engine allows it when no reserve is configured"
        );
        assertEquals(
                -1L,
                selectWithReserve(0.5),
                "and refuses it once the same half-heart reserve is set"
        );
    }

    private static long selectWithReserve(double selfSafetyReserve) {
        return CrystalDecisionEngine.selectBest(
                List.of(new CrystalDecisionEngine.Candidate(
                        1L, 40.0, 9.6, 0.0, false, 20.0, 2.0, true
                )),
                new CrystalDecisionEngine.Limits(
                        64, 2.0, 12.0, 4.0, selfSafetyReserve, 4.0, 1.5, 0.05
                ),
                10.0
        );
    }

    @Test
    void localSelectionIsStableUnderReordering() {
        // 1.21.4 keeps the first of two equal scores, so its choice depends on
        // the sort applied before scoring. That sort is by distance then by a
        // stable identity, so the result is deterministic. Proving it here means
        // a future change to that sort cannot silently randomise which crystal
        // gets placed.
        List<Placement> placements = new ArrayList<>(List.of(
                new Placement(1L, 10.0, 2.0, 3.0),
                new Placement(2L, 10.0, 2.0, 3.0),
                new Placement(3L, 12.0, 2.0, 3.0),
                new Placement(4L, 12.0, 2.0, 3.0)
        ));
        long expected = bestOf(placements);
        assertEquals(3L, expected, "the first of the two best-scoring placements");

        Random random = new Random(20260102L);
        for (int trial = 0; trial < 200; trial++) {
            Collections.shuffle(placements, random);
            placements.sort(Comparator.comparingLong(Placement::id));
            assertEquals(expected, bestOf(placements), "ordering must not change the pick");
        }
    }

    private static long bestOf(List<Placement> placements) {
        long bestId = -1L;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Placement placement : placements) {
            double score = CrystalScoring.score(
                    placement.targetDamage(),
                    placement.selfDamage(),
                    placement.distance(),
                    placement.distance(),
                    1.5
            );
            if (score > bestScore) {
                bestScore = score;
                bestId = placement.id();
            }
        }
        return bestId;
    }

    private record Placement(
            long id,
            double targetDamage,
            double selfDamage,
            double distance
    ) {
    }
}
