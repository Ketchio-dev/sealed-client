package dev.b2tclient.v26.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraControlDecisionEngine26Test {
    private static final ElytraControlDecisionEngine26.Configuration CONFIG =
            ElytraControlDecisionEngine26.Configuration.DEFAULT;

    @Test
    void appliesStrictHorizontalAccelerationBudgetDuringRealGlide() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(101L);

        ElytraControlDecisionEngine26.Decision decision =
                engine.decide(forward(101L, 1.0), CONFIG);

        assertTrue(decision.apply());
        assertTrue(decision.applyHorizontal());
        assertFalse(decision.applyVertical());
        assertFalse(decision.applyPitch());
        assertEquals(0.04, Math.hypot(
                decision.nextVelocityX(),
                decision.nextVelocityZ()
        ), 1.0E-9);
        assertEquals(0.04, decision.accelerationBudget(), 1.0E-9);
    }

    @Test
    void sharedHighPingScaleReducesTargetAndAcceleration() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(102L);

        ElytraControlDecisionEngine26.Decision decision =
                engine.decide(forward(102L, 0.45), CONFIG);

        assertTrue(decision.apply());
        assertEquals(0.018, decision.accelerationBudget(), 1.0E-9);
        assertEquals(0.018, Math.hypot(
                decision.nextVelocityX(),
                decision.nextVelocityZ()
        ), 1.0E-9);
        assertEquals(0.45, decision.safetyScale(), 1.0E-9);
    }

    @Test
    void pausedNetworkAndNonGlidingStateFailClosed() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(103L);

        ElytraControlDecisionEngine26.Observation paused =
                withSafety(forward(103L, 1.0), false, 0.0);
        assertEquals(
                ElytraControlDecisionEngine26.BlockReason.NETWORK_SAFETY,
                engine.decide(paused, CONFIG).blockReason()
        );

        ElytraControlDecisionEngine26.Observation walking =
                withFallFlying(forward(103L, 1.0), false);
        assertEquals(
                ElytraControlDecisionEngine26.BlockReason.NOT_GLIDING,
                engine.decide(walking, CONFIG).blockReason()
        );
    }

    @Test
    void ascentUsesBoundedVerticalVelocityAndPitch() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(104L);
        ElytraControlDecisionEngine26.Observation ascent =
                vertical(104L, true, false, 0.0F);

        ElytraControlDecisionEngine26.Decision decision =
                engine.decide(ascent, CONFIG);

        assertTrue(decision.applyVertical());
        assertTrue(decision.applyPitch());
        assertEquals(0.04, decision.nextVelocityY(), 1.0E-9);
        assertEquals(-2.0F, decision.nextPitchDegrees(), 1.0E-6F);
        assertTrue(Math.abs(decision.nextVelocityY())
                <= decision.accelerationBudget() + 1.0E-9);
    }

    @Test
    void descentApproachesConfiguredDirectionWithoutVelocityJump() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(105L);
        ElytraControlDecisionEngine26.Observation descent =
                vertical(105L, false, true, 0.0F);

        ElytraControlDecisionEngine26.Decision decision =
                engine.decide(descent, CONFIG);

        assertEquals(-0.04, decision.nextVelocityY(), 1.0E-9);
        assertEquals(2.0F, decision.nextPitchDegrees(), 1.0E-6F);
    }

    @Test
    void manualPitchChangeYieldsRotationForBoundedTicks() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(106L);
        ElytraControlDecisionEngine26.Decision automated =
                engine.decide(
                        vertical(106L, true, false, 0.0F),
                        CONFIG
                );
        engine.commit(automated, true);
        assertTrue(engine.snapshot().pitchOwnedLastTick());

        ElytraControlDecisionEngine26.Decision manual =
                engine.decide(
                        vertical(106L, true, false, 12.0F),
                        CONFIG
                );

        assertTrue(manual.applyVertical());
        assertFalse(manual.applyPitch());
        assertTrue(manual.manualPitchSuppressionTicks() > 0);
        assertFalse(engine.snapshot().pitchOwnedLastTick());
    }

    @Test
    void deniedDecisionDoesNotAcquirePitchOwnership() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(107L);
        ElytraControlDecisionEngine26.Decision decision =
                engine.decide(
                        vertical(107L, true, false, 0.0F),
                        CONFIG
                );

        engine.commit(decision, false);

        assertFalse(engine.snapshot().pitchOwnedLastTick());
    }

    @Test
    void pitchSuppressionRetainsVelocityActionsWithoutRotationClaim() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(112L);
        ElytraControlDecisionEngine26.Decision original =
                engine.decide(
                        vertical(112L, true, false, 0.0F),
                        CONFIG
                );

        ElytraControlDecisionEngine26.Decision suppressed =
                ElytraControlAutomation26.suppressPitch(original);

        assertTrue(suppressed.apply());
        assertTrue(suppressed.applyVertical());
        assertFalse(suppressed.applyPitch());
        assertEquals(
                original.nextVelocityY(),
                suppressed.nextVelocityY(),
                1.0E-9
        );
    }

    @Test
    void oppositeVerticalInputsAndNoDirectionDoNothing() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(108L);
        ElytraControlDecisionEngine26.Observation both =
                new ElytraControlDecisionEngine26.Observation(
                        108L,
                        true,
                        true,
                        true,
                        true,
                        1.0,
                        true,
                        false,
                        false,
                        false,
                        false,
                        0.0,
                        0.0,
                        true,
                        true,
                        0.0,
                        0.0F,
                        0.0,
                        0.0,
                        0.0
                );

        assertEquals(
                ElytraControlDecisionEngine26.BlockReason.NO_INPUT,
                engine.decide(both, CONFIG).blockReason()
        );
    }

    @Test
    void vectorApproachNeverExceedsEuclideanBudget() {
        double[] next = ElytraControlDecisionEngine26.approachVector(
                0.0,
                0.0,
                1.0,
                1.0,
                0.04
        );

        assertEquals(0.04, Math.hypot(next[0], next[1]), 1.0E-9);
        assertThrows(
                IllegalArgumentException.class,
                () -> ElytraControlDecisionEngine26.approachVector(
                        0.0,
                        0.0,
                        1.0,
                        1.0,
                        -0.1
                )
        );
    }

    @Test
    void reconnectClearsManualPitchOwnership() {
        ElytraControlDecisionEngine26 engine =
                warmedEngine(109L);
        ElytraControlDecisionEngine26.Decision applied =
                engine.decide(
                        vertical(109L, true, false, 0.0F),
                        CONFIG
                );
        engine.commit(applied, true);

        ElytraControlDecisionEngine26.Decision reconnect =
                engine.decide(forward(110L, 1.0), CONFIG);

        assertEquals(
                ElytraControlDecisionEngine26.BlockReason.SESSION_WARMUP,
                reconnect.blockReason()
        );
        assertFalse(engine.snapshot().pitchOwnedLastTick());
        assertEquals(0, engine.snapshot().manualPitchSuppressionTicks());
    }

    @Test
    void invalidConfigurationAndObservationAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ElytraControlDecisionEngine26.Configuration(
                        2.1,
                        0.04,
                        0.25
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ElytraControlAutomation26.Configuration(
                        1.25,
                        0.5,
                        0.25
                )
        );

        ElytraControlDecisionEngine26 engine =
                warmedEngine(111L);
        ElytraControlDecisionEngine26.Observation invalid =
                withSafety(forward(111L, 1.0), true, Double.NaN);
        assertEquals(
                ElytraControlDecisionEngine26.BlockReason.INVALID,
                engine.decide(invalid, CONFIG).blockReason()
        );
    }

    private static ElytraControlDecisionEngine26 warmedEngine(
            long session
    ) {
        ElytraControlDecisionEngine26 engine =
                new ElytraControlDecisionEngine26();
        assertEquals(
                ElytraControlDecisionEngine26.BlockReason.SESSION_WARMUP,
                engine.decide(forward(session, 1.0), CONFIG).blockReason()
        );
        return engine;
    }

    private static ElytraControlDecisionEngine26.Observation forward(
            long session,
            double safetyScale
    ) {
        return new ElytraControlDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                safetyScale,
                true,
                false,
                false,
                false,
                false,
                0.0,
                1.0,
                false,
                false,
                0.0,
                0.0F,
                0.0,
                0.0,
                0.0
        );
    }

    private static ElytraControlDecisionEngine26.Observation vertical(
            long session,
            boolean ascend,
            boolean descend,
            float pitch
    ) {
        return new ElytraControlDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                1.0,
                true,
                false,
                false,
                false,
                false,
                0.0,
                0.0,
                ascend,
                descend,
                0.0,
                pitch,
                0.0,
                0.0,
                0.0
        );
    }

    private static ElytraControlDecisionEngine26.Observation withSafety(
            ElytraControlDecisionEngine26.Observation source,
            boolean allowed,
            double scale
    ) {
        return new ElytraControlDecisionEngine26.Observation(
                source.sessionKey(),
                source.enabled(),
                source.sessionReady(),
                source.screenClear(),
                allowed,
                scale,
                source.fallFlying(),
                source.passenger(),
                source.inWater(),
                source.inLava(),
                source.horizontalCollision(),
                source.inputStrafe(),
                source.inputForward(),
                source.ascend(),
                source.descend(),
                source.yawDegrees(),
                source.pitchDegrees(),
                source.velocityX(),
                source.velocityY(),
                source.velocityZ()
        );
    }

    private static ElytraControlDecisionEngine26.Observation withFallFlying(
            ElytraControlDecisionEngine26.Observation source,
            boolean fallFlying
    ) {
        return new ElytraControlDecisionEngine26.Observation(
                source.sessionKey(),
                source.enabled(),
                source.sessionReady(),
                source.screenClear(),
                source.safetyAllowed(),
                source.safetyScale(),
                fallFlying,
                source.passenger(),
                source.inWater(),
                source.inLava(),
                source.horizontalCollision(),
                source.inputStrafe(),
                source.inputForward(),
                source.ascend(),
                source.descend(),
                source.yawDegrees(),
                source.pitchDegrees(),
                source.velocityX(),
                source.velocityY(),
                source.velocityZ()
        );
    }
}
