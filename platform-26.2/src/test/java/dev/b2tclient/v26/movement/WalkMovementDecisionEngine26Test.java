package dev.b2tclient.v26.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WalkMovementDecisionEngine26Test {
    private static final double EPSILON = 1.0E-9;

    @Test
    void safeWalkStopsOnlyManualGroundMovementAtUnsupportedEdge() {
        var moving = control(true, true, false, false);
        assertTrue(WalkMovementDecisionEngine26.shouldStopAtEdge(
                new WalkMovementDecisionEngine26.EdgeObservation(
                        moving,
                        1.0,
                        0.0,
                        0.45,
                        true
                )
        ));
        assertFalse(WalkMovementDecisionEngine26.shouldStopAtEdge(
                new WalkMovementDecisionEngine26.EdgeObservation(
                        control(false, true, false, false),
                        1.0,
                        0.0,
                        0.45,
                        true
                )
        ));
        assertFalse(WalkMovementDecisionEngine26.shouldStopAtEdge(
                new WalkMovementDecisionEngine26.EdgeObservation(
                        moving,
                        1.0,
                        0.0,
                        0.45,
                        false
                )
        ));
    }

    @Test
    void steeringIsPerTickCappedAndServerScaleIsConservative() {
        var plan = WalkMovementDecisionEngine26.steer(
                new WalkMovementDecisionEngine26.SteeringObservation(
                        control(false, true, false, false),
                        0.0,
                        0.0,
                        3.0,
                        4.0,
                        0.20,
                        0.03,
                        0.45,
                        true,
                        true
                )
        ).orElseThrow();

        assertEquals(0.09, plan.horizontalSpeed(), EPSILON);
        assertEquals(0.054, plan.deltaX(), EPSILON);
        assertEquals(0.072, plan.deltaZ(), EPSILON);
        assertFalse(plan.stop());
    }

    @Test
    void autonomousSteeringYieldsToEveryManualOverride() {
        for (WalkMovementDecisionEngine26.ControlState overridden : List.of(
                control(true, true, false, false),
                control(false, true, true, false),
                control(false, true, false, true)
        )) {
            assertTrue(WalkMovementDecisionEngine26.steer(
                    new WalkMovementDecisionEngine26.SteeringObservation(
                            overridden,
                            0.0,
                            0.0,
                            1.0,
                            0.0,
                            0.2,
                            0.03,
                            1.0,
                            true,
                            true
                    )
            ).isEmpty());
        }
    }

    @Test
    void steeringStopsExactlyInsideTolerance() {
        var plan = WalkMovementDecisionEngine26.steer(
                new WalkMovementDecisionEngine26.SteeringObservation(
                        control(false, true, false, false),
                        0.49,
                        0.50,
                        0.50,
                        0.50,
                        0.12,
                        0.04,
                        1.0,
                        true,
                        true
                )
        ).orElseThrow();

        assertTrue(plan.stop());
        assertEquals(0.0, plan.horizontalSpeed(), EPSILON);
    }

    @Test
    void holeSelectionNeverReadsBeyondInspectionBudget() {
        var unsafe = hole(1L, 1.0, false, true, true);
        var nearestButOutOfBudget = hole(2L, 0.01, true, true, true);

        var selection = WalkMovementDecisionEngine26.selectHole(
                List.of(unsafe, nearestButOutOfBudget),
                1,
                3.75,
                1.25
        );

        assertEquals(1, selection.inspected());
        assertTrue(selection.candidate().isEmpty());
    }

    @Test
    void holeSelectionRejectsUnloadedBlockedAndOutOfRangeCandidates() {
        var selection = WalkMovementDecisionEngine26.selectHole(
                List.of(
                        hole(1L, 0.1, true, false, true),
                        hole(2L, 0.2, true, true, false),
                        new WalkMovementDecisionEngine26.HoleCandidate(
                                3L,
                                10.5,
                                64.0,
                                0.5,
                                100.0,
                                0.0,
                                true,
                                true,
                                true
                        ),
                        hole(4L, 0.5, true, true, true)
                ),
                4,
                3.75,
                1.25
        );

        assertEquals(4L, selection.candidate().orElseThrow().key());
    }

    @Test
    void holeTieBreakIsDeterministic() {
        var selection = WalkMovementDecisionEngine26.selectHole(
                List.of(
                        hole(9L, 1.0, true, true, true),
                        hole(3L, 1.0, true, true, true)
                ),
                8,
                3.75,
                1.25
        );

        assertEquals(3L, selection.candidate().orElseThrow().key());
    }

    @Test
    void stepRampsHeightAndAppliesServerCorrectionScale() {
        var plan = WalkMovementDecisionEngine26.step(
                new WalkMovementDecisionEngine26.StepObservation(
                        control(true, true, false, false),
                        0.6,
                        1.5,
                        0.2,
                        0.45
                )
        ).orElseThrow();

        assertEquals(0.8, plan.targetHeight(), EPSILON);
        assertTrue(plan.modifierRequired());
    }

    @Test
    void stepNeverOverridesJumpCrouchOrPausedNetwork() {
        assertTrue(step(control(true, true, true, false), 1.0).isEmpty());
        assertTrue(step(control(true, true, false, true), 1.0).isEmpty());
        assertTrue(step(control(true, true, false, false), 0.0).isEmpty());
    }

    @Test
    void everyAssistFailsClosedForInvalidSessionAndUnsafeMovementState() {
        for (WalkMovementDecisionEngine26.ControlState blocked : List.of(
                new WalkMovementDecisionEngine26.ControlState(
                        false, true, false, false, false,
                        false, true, true, false, false
                ),
                new WalkMovementDecisionEngine26.ControlState(
                        true, false, false, false, false,
                        false, true, true, false, false
                ),
                new WalkMovementDecisionEngine26.ControlState(
                        true, true, true, false, false,
                        false, true, true, false, false
                ),
                new WalkMovementDecisionEngine26.ControlState(
                        true, true, false, true, false,
                        false, true, true, false, false
                ),
                new WalkMovementDecisionEngine26.ControlState(
                        true, true, false, false, true,
                        false, true, true, false, false
                ),
                new WalkMovementDecisionEngine26.ControlState(
                        true, true, false, false, false,
                        true, true, true, false, false
                )
        )) {
            assertFalse(WalkMovementDecisionEngine26.shouldStopAtEdge(
                    new WalkMovementDecisionEngine26.EdgeObservation(
                            blocked,
                            1.0,
                            0.0,
                            0.45,
                            true
                    )
            ));
            assertTrue(WalkMovementDecisionEngine26.step(
                    new WalkMovementDecisionEngine26.StepObservation(
                            blocked,
                            0.6,
                            1.0,
                            0.2,
                            1.0
                    )
            ).isEmpty());
        }
    }

    private static java.util.Optional<WalkMovementDecisionEngine26.StepPlan> step(
            WalkMovementDecisionEngine26.ControlState control,
            double scale
    ) {
        return WalkMovementDecisionEngine26.step(
                new WalkMovementDecisionEngine26.StepObservation(
                        control,
                        0.6,
                        1.0,
                        0.2,
                        scale
                )
        );
    }

    private static WalkMovementDecisionEngine26.ControlState control(
            boolean directional,
            boolean onGround,
            boolean jump,
            boolean crouch
    ) {
        return new WalkMovementDecisionEngine26.ControlState(
                true,
                true,
                false,
                false,
                false,
                false,
                onGround,
                directional,
                jump,
                crouch
        );
    }

    private static WalkMovementDecisionEngine26.HoleCandidate hole(
            long key,
            double distanceSquared,
            boolean safe,
            boolean loaded,
            boolean pathClear
    ) {
        return new WalkMovementDecisionEngine26.HoleCandidate(
                key,
                key + 0.5,
                64.0,
                0.5,
                distanceSquared,
                0.0,
                safe,
                loaded,
                pathClear
        );
    }
}
