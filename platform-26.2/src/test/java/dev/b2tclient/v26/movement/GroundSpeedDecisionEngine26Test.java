package dev.b2tclient.v26.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundSpeedDecisionEngine26Test {
    private final GroundSpeedDecisionEngine26 engine =
            new GroundSpeedDecisionEngine26();

    @Test
    void forwardInputApproachesTargetWithinAccelerationBudget() {
        GroundSpeedDecisionEngine26.Decision decision = engine.decide(
                safeObservation(0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );

        assertTrue(decision.apply());
        assertEquals(0.0, decision.nextVelocityX(), 1.0E-12);
        assertEquals(0.06, decision.nextVelocityZ(), 1.0E-12);
        assertEquals(0.31, decision.targetSpeed(), 1.0E-12);
        assertEquals(1.0, decision.safetyScale(), 1.0E-12);
    }

    @Test
    void diagonalInputIsNormalizedAndAccelerationRemainsBounded() {
        GroundSpeedDecisionEngine26.Decision decision = engine.decide(
                safeObservation(1.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );

        assertTrue(decision.apply());
        assertEquals(
                0.06,
                Math.hypot(decision.nextVelocityX(), decision.nextVelocityZ()),
                1.0E-12
        );
        assertEquals(
                decision.nextVelocityX(),
                decision.nextVelocityZ(),
                1.0E-12
        );
    }

    @Test
    void fractionalInputCannotProduceFullTargetSpeed() {
        GroundSpeedDecisionEngine26.Decision decision = engine.decide(
                safeObservation(0.0, 0.25, 0.0, 0.0, 0.0, 1.0),
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );

        assertTrue(decision.apply());
        assertEquals(0.0775, decision.targetSpeed(), 1.0E-12);
        assertEquals(0.06, decision.nextVelocityZ(), 1.0E-12);
    }

    @Test
    void yawRotatesRealInputInsteadOfInventingMovement() {
        GroundSpeedDecisionEngine26.Decision decision = engine.decide(
                safeObservation(0.0, 1.0, 90.0, 0.0, 0.0, 1.0),
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );

        assertTrue(decision.apply());
        assertEquals(-0.06, decision.nextVelocityX(), 1.0E-12);
        assertEquals(0.0, decision.nextVelocityZ(), 1.0E-12);
    }

    @Test
    void speedAboveTargetDeceleratesGradually() {
        GroundSpeedDecisionEngine26.Decision decision = engine.decide(
                safeObservation(0.0, 1.0, 0.0, 0.0, 0.50, 1.0),
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );

        assertTrue(decision.apply());
        assertEquals(0.44, decision.nextVelocityZ(), 1.0E-12);
        assertEquals(
                0.06,
                Math.abs(0.50 - decision.nextVelocityZ()),
                1.0E-12
        );
    }

    @Test
    void sharedNetworkSafetyScaleReducesTargetAndAcceleration() {
        GroundSpeedDecisionEngine26.Decision decision = engine.decide(
                safeObservation(0.0, 1.0, 0.0, 0.0, 0.0, 0.50),
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );

        assertTrue(decision.apply());
        assertEquals(0.50, decision.safetyScale(), 1.0E-12);
        assertEquals(0.155, decision.targetSpeed(), 1.0E-12);
        assertEquals(0.03, decision.accelerationBudget(), 1.0E-12);
        assertEquals(0.03, decision.nextVelocityZ(), 1.0E-12);
    }

    @Test
    void blockedSharedSafetyDecisionFailsClosed() {
        GroundSpeedDecisionEngine26.Observation blocked =
                safeObservation(0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
                        .withSafety(false, 0.0);

        GroundSpeedDecisionEngine26.Decision decision = engine.decide(
                blocked,
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );

        assertFalse(decision.apply());
        assertEquals(
                GroundSpeedDecisionEngine26.BlockReason.NETWORK_SAFETY,
                decision.blockReason()
        );
    }

    @Test
    void noInputAndUnsafeStatesFailClosed() {
        GroundSpeedDecisionEngine26.Decision noInput = engine.decide(
                safeObservation(0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
                GroundSpeedDecisionEngine26.Configuration.DEFAULT
        );
        GroundSpeedDecisionEngine26.Observation unsafe =
                new GroundSpeedDecisionEngine26.Observation(
                        true, true, true, true, true, true,
                        true, 1.0,
                        false, false, false, false, false, false, false, false,
                        0.0, 1.0, 0.0, 0.0, 0.0
                );

        assertFalse(noInput.apply());
        assertEquals(
                GroundSpeedDecisionEngine26.BlockReason.NO_INPUT,
                noInput.blockReason()
        );
        assertEquals(
                GroundSpeedDecisionEngine26.BlockReason.UNSAFE_MOVEMENT_STATE,
                engine.decide(
                        unsafe,
                        GroundSpeedDecisionEngine26.Configuration.DEFAULT
                ).blockReason()
        );
    }

    @Test
    void nonFiniteObservationAndInvalidConfigurationFailClosed() {
        GroundSpeedDecisionEngine26.Observation invalid =
                safeObservation(
                        0.0,
                        1.0,
                        Double.NaN,
                        0.0,
                        0.0,
                        1.0
                );

        assertEquals(
                GroundSpeedDecisionEngine26.BlockReason.INVALID,
                engine.decide(
                        invalid,
                        GroundSpeedDecisionEngine26.Configuration.DEFAULT
                ).blockReason()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundSpeedDecisionEngine26.Configuration(
                        0.31, Double.NaN
                )
        );
    }

    private static GroundSpeedDecisionEngine26.Observation safeObservation(
            double strafe,
            double forward,
            double yaw,
            double velocityX,
            double velocityZ,
            double safetyScale
    ) {
        return new GroundSpeedDecisionEngine26.Observation(
                true, // enabled
                true, // sessionActive
                true, // playerPresent
                true, // playerAlive
                true, // screenClear
                true, // networkReady
                safetyScale > 0.0,
                safetyScale,
                true, // onGround
                false, // passenger
                false, // inWater
                false, // inLava
                false, // swimming
                false, // fallFlying
                false, // flying
                false, // horizontalCollision
                strafe,
                forward,
                yaw,
                velocityX,
                velocityZ
        );
    }
}
