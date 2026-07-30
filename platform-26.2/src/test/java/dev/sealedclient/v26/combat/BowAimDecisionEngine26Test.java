package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BowAimDecisionEngine26Test {
    private static final BowAimDecisionEngine26.Vector3 ORIGIN =
            new BowAimDecisionEngine26.Vector3(0.0, 1.6, 0.0);
    private static final BowAimDecisionEngine26.Limits LIMITS =
            new BowAimDecisionEngine26.Limits(
                    48.0,
                    0.05,
                    40.0,
                    90.0,
                    12.0
            );

    @Test
    void solvesLowArcForStationaryVisibleEnemy() {
        BowAimDecisionEngine26.Solution solution =
                BowAimDecisionEngine26.select(
                        ORIGIN,
                        0.0,
                        0.0,
                        3.0,
                        List.of(candidate(
                                7,
                                0.0,
                                1.6,
                                30.0,
                                900.0
                        )),
                        LIMITS
                ).orElseThrow();

        assertEquals(7, solution.targetEntityId());
        assertEquals(0.0, solution.targetYaw(), 1.0E-6);
        assertTrue(solution.targetPitch() < 0.0);
        assertTrue(solution.flightTicks() > 10.0);
    }

    @Test
    void leadsBoundedTargetVelocity() {
        BowAimDecisionEngine26.Candidate moving =
                new BowAimDecisionEngine26.Candidate(
                        3,
                        new BowAimDecisionEngine26.Vector3(
                                0.0,
                                1.6,
                                24.0
                        ),
                        new BowAimDecisionEngine26.Vector3(
                                0.2,
                                0.0,
                                0.0
                        ),
                        576.0,
                        false,
                        true,
                        true,
                        false
                );

        BowAimDecisionEngine26.Solution solution =
                BowAimDecisionEngine26.select(
                        ORIGIN,
                        0.0,
                        0.0,
                        3.0,
                        List.of(moving),
                        LIMITS
                ).orElseThrow();

        assertTrue(solution.predictedPosition().x() > 1.0);
        assertTrue(solution.targetYaw() < 0.0);
        assertTrue(solution.flightTicks() <= LIMITS.maximumLeadTicks());
    }

    @Test
    void excludesFriendOccludedDeadSpectatorAndOutOfRangeTargets() {
        List<BowAimDecisionEngine26.Candidate> candidates = List.of(
                candidate(1, 0, 1.6, 10, 100, true, true, true, false),
                candidate(2, 0, 1.6, 10, 100, false, false, true, false),
                candidate(3, 0, 1.6, 10, 100, false, true, false, false),
                candidate(4, 0, 1.6, 10, 100, false, true, true, true),
                candidate(5, 0, 1.6, 60, 3_600, false, true, true, false)
        );

        assertTrue(BowAimDecisionEngine26.select(
                ORIGIN,
                0.0,
                0.0,
                3.0,
                candidates,
                LIMITS
        ).isEmpty());
    }

    @Test
    void fovRejectsBehindTargetAndRotationRateIsBounded() {
        BowAimDecisionEngine26.Limits narrow =
                new BowAimDecisionEngine26.Limits(
                        48.0,
                        0.05,
                        40.0,
                        45.0,
                        5.0
                );
        assertTrue(BowAimDecisionEngine26.select(
                ORIGIN,
                0.0,
                0.0,
                3.0,
                List.of(candidate(1, 0.0, 1.6, -20.0, 400.0)),
                narrow
        ).isEmpty());

        BowAimDecisionEngine26.Solution solution =
                BowAimDecisionEngine26.select(
                        ORIGIN,
                        0.0,
                        0.0,
                        3.0,
                        List.of(candidate(2, 10.0, 1.6, 10.0, 200.0)),
                        new BowAimDecisionEngine26.Limits(
                                48.0,
                                0.05,
                                40.0,
                                90.0,
                                5.0
                        )
                ).orElseThrow();
        double appliedDelta = Math.hypot(
                BowAimDecisionEngine26.wrapDegrees(
                        solution.appliedYaw()
                ),
                solution.appliedPitch()
        );
        assertTrue(appliedDelta <= 5.0 + 1.0E-6);
    }

    @Test
    void impossibleArcAndInvalidNumbersFailClosed() {
        assertTrue(BowAimDecisionEngine26.ballisticRotation(
                ORIGIN,
                new BowAimDecisionEngine26.Vector3(0.0, 100.0, 10.0),
                0.2,
                0.05
        ).isEmpty());
        assertTrue(BowAimDecisionEngine26.select(
                ORIGIN,
                Double.NaN,
                0.0,
                3.0,
                List.of(candidate(1, 0.0, 1.6, 10.0, 100.0)),
                LIMITS
        ).isEmpty());
        assertFalse(Double.isFinite(
                BowAimDecisionEngine26.wrapDegrees(Double.NaN)
        ));
    }

    @Test
    void candidateEvaluationIsCappedAtSixtyFour() {
        List<BowAimDecisionEngine26.Candidate> candidates =
                new ArrayList<>();
        for (int index = 0;
             index < BowAimDecisionEngine26.MAXIMUM_CANDIDATES;
             index++) {
            candidates.add(candidate(
                    index,
                    0.0,
                    1.6,
                    20.0 + index * 0.01,
                    Math.pow(20.0 + index * 0.01, 2.0)
            ));
        }
        candidates.add(candidate(
                999,
                0.0,
                1.6,
                2.0,
                4.0
        ));

        BowAimDecisionEngine26.Solution selected =
                BowAimDecisionEngine26.select(
                        ORIGIN,
                        0.0,
                        0.0,
                        3.0,
                        candidates,
                        LIMITS
                ).orElseThrow();

        assertEquals(0, selected.targetEntityId());
    }

    @Test
    void configurationRejectsUnsafeBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatBowAimAutomation26.Configuration(
                        48,
                        3,
                        3.15,
                        0.05,
                        40,
                        181,
                        12,
                        5,
                        0.75,
                        5
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatBowAimAutomation26.Configuration(
                        48,
                        3,
                        3.15,
                        0.05,
                        40,
                        70,
                        12,
                        0,
                        0.75,
                        5
                )
        );
    }

    @Test
    void manualOverrideDetectionHandlesYawWrapAndFailsClosed() {
        assertFalse(CombatBowAimAutomation26.manualOverrideDetected(
                -179.8,
                10.0,
                179.8,
                10.0,
                0.75
        ));
        assertTrue(CombatBowAimAutomation26.manualOverrideDetected(
                -178.0,
                10.0,
                179.8,
                10.0,
                0.75
        ));
        assertTrue(CombatBowAimAutomation26.manualOverrideDetected(
                Double.NaN,
                0.0,
                0.0,
                0.0,
                0.75
        ));
    }

    private static BowAimDecisionEngine26.Candidate candidate(
            int id,
            double x,
            double y,
            double z,
            double distanceSquared
    ) {
        return candidate(
                id,
                x,
                y,
                z,
                distanceSquared,
                false,
                true,
                true,
                false
        );
    }

    private static BowAimDecisionEngine26.Candidate candidate(
            int id,
            double x,
            double y,
            double z,
            double distanceSquared,
            boolean friend,
            boolean lineOfSight,
            boolean alive,
            boolean spectator
    ) {
        return new BowAimDecisionEngine26.Candidate(
                id,
                new BowAimDecisionEngine26.Vector3(x, y, z),
                new BowAimDecisionEngine26.Vector3(0.0, 0.0, 0.0),
                distanceSquared,
                friend,
                lineOfSight,
                alive,
                spectator
        );
    }
}
