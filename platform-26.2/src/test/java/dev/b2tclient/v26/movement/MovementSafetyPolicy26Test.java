package dev.b2tclient.v26.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementSafetyPolicy26Test {
    private static final int NORMAL_LATENCY_MILLIS = 50;
    private static final long FRESH_INBOUND_MILLIS = 20L;

    @Test
    void initialAndReconnectedSessionsRequireExactWarmup() {
        MovementSafetyPolicy26 policy = new MovementSafetyPolicy26();
        Object first = new Object();

        assertPaused(observe(policy, first, 0.0, 0L));
        assertPaused(observe(policy, first, 0.0, 0L));
        assertActive(observe(policy, first, 0.0, 0L));

        Object second = new Object();
        MovementSafetyPolicy26.Decision reconnected =
                observe(policy, second, 500.0, 7L);
        assertPaused(reconnected);
        assertEquals(
                MovementSafetyPolicy26.Reason.RECONNECT_WARMUP,
                reconnected.reason()
        );
        assertPaused(observe(policy, second, 500.0, 7L));
        assertActive(observe(policy, second, 500.0, 7L));
        assertEquals(7L, policy.snapshot().lastCorrectionSequence());
    }

    @Test
    void sessionTokensUseIdentityRatherThanValueEquality() {
        MovementSafetyPolicy26 policy = new MovementSafetyPolicy26();
        EqualToken first = new EqualToken(1);
        EqualToken equalButReconnected = new EqualToken(1);
        warm(policy, first);

        assertEquals(first, equalButReconnected);
        MovementSafetyPolicy26.Decision decision =
                observe(policy, equalButReconnected, 0.0, 0L);

        assertPaused(decision);
        assertEquals(
                MovementSafetyPolicy26.Reason.RECONNECT_WARMUP,
                decision.reason()
        );
    }

    @Test
    void latencyAndInboundThresholdsFailClosedWithRecoveryHysteresis() {
        Harness harness = warmedPolicy();
        MovementSafetyPolicy26.Configuration defaults =
                harness.policy().configuration();

        assertActive(observeNetwork(
                harness,
                defaults.highLatencyMillis() - 149,
                FRESH_INBOUND_MILLIS
        ));
        assertActive(observeNetwork(
                harness,
                defaults.highLatencyMillis() - 1,
                FRESH_INBOUND_MILLIS
        ));
        MovementSafetyPolicy26.Decision high = observeNetwork(
                harness,
                defaults.highLatencyMillis(),
                FRESH_INBOUND_MILLIS
        );
        assertSlowdown(high);
        assertEquals(MovementSafetyPolicy26.Reason.HIGH_LATENCY, high.reason());

        MovementSafetyPolicy26.Decision severe = observeNetwork(
                harness,
                defaults.severeLatencyMillis(),
                FRESH_INBOUND_MILLIS
        );
        assertPaused(severe);
        assertEquals(
                MovementSafetyPolicy26.Reason.SEVERE_LATENCY,
                severe.reason()
        );

        for (int tick = 0; tick < defaults.networkRecoveryTicks(); tick++) {
            MovementSafetyPolicy26.Decision recovery = observeNetwork(
                    harness,
                    NORMAL_LATENCY_MILLIS,
                    FRESH_INBOUND_MILLIS
            );
            assertPaused(recovery);
            assertEquals(
                    MovementSafetyPolicy26.Reason.NETWORK_RECOVERY,
                    recovery.reason()
            );
        }
        assertActive(observeNetwork(
                harness,
                NORMAL_LATENCY_MILLIS,
                FRESH_INBOUND_MILLIS
        ));

        assertSlowdown(observeNetwork(
                harness,
                NORMAL_LATENCY_MILLIS,
                defaults.staleInboundMillis()
        ));
        MovementSafetyPolicy26.Decision timeout = observeNetwork(
                harness,
                NORMAL_LATENCY_MILLIS,
                defaults.timedOutInboundMillis()
        );
        assertPaused(timeout);
        assertEquals(
                MovementSafetyPolicy26.Reason.INBOUND_TIMEOUT,
                timeout.reason()
        );
    }

    @Test
    void jitterSlowsAndRequiresStableRecoveryTicks() {
        Harness harness = warmedPolicy();
        MovementSafetyPolicy26.Configuration defaults =
                harness.policy().configuration();

        MovementSafetyPolicy26.Decision jitter = observeNetwork(
                harness,
                NORMAL_LATENCY_MILLIS + defaults.jitterBurstMillis(),
                FRESH_INBOUND_MILLIS
        );
        assertSlowdown(jitter);
        assertEquals(
                MovementSafetyPolicy26.Reason.LATENCY_JITTER,
                jitter.reason()
        );

        // Returning to the baseline is itself an equal-sized jitter sample.
        assertSlowdown(observeNetwork(
                harness,
                NORMAL_LATENCY_MILLIS,
                FRESH_INBOUND_MILLIS
        ));
        for (int tick = 0; tick < defaults.networkRecoveryTicks(); tick++) {
            assertSlowdown(observeNetwork(
                    harness,
                    NORMAL_LATENCY_MILLIS,
                    FRESH_INBOUND_MILLIS
            ));
        }
        assertActive(observeNetwork(
                harness,
                NORMAL_LATENCY_MILLIS,
                FRESH_INBOUND_MILLIS
        ));
    }

    @Test
    void oneExplicitCorrectionSlowsForExactlyConfiguredTicks() {
        Harness harness = warmedPolicy();
        MovementSafetyPolicy26.Configuration defaults =
                harness.policy().configuration();
        harness.correctionSequence++;

        MovementSafetyPolicy26.Decision first = harness.tick();
        assertSlowdown(first);
        assertEquals(
                MovementSafetyPolicy26.Reason.SERVER_CORRECTION,
                first.reason()
        );
        for (int tick = 1; tick < defaults.correctionSlowdownTicks(); tick++) {
            assertSlowdown(harness.tick());
        }
        assertActive(harness.tick());
    }

    @Test
    void repeatedOrBatchedCorrectionsPauseForExactlyConfiguredTicks() {
        Harness repeated = warmedPolicy();
        MovementSafetyPolicy26.Configuration defaults =
                repeated.policy().configuration();
        repeated.correctionSequence++;
        assertSlowdown(repeated.tick());
        repeated.correctionSequence++;
        MovementSafetyPolicy26.Decision second = repeated.tick();
        assertPaused(second);
        assertEquals(
                MovementSafetyPolicy26.Reason.REPEATED_SERVER_CORRECTION,
                second.reason()
        );
        for (int tick = 1;
             tick < defaults.repeatedCorrectionPauseTicks();
             tick++) {
            assertPaused(repeated.tick());
        }
        assertActive(repeated.tick());

        Harness batched = warmedPolicy();
        batched.correctionSequence += 2L;
        assertPaused(batched.tick());
    }

    @Test
    void correctionOutsideWindowIsTreatedAsANewFirstCorrection() {
        Harness harness = warmedPolicy();
        MovementSafetyPolicy26.Configuration defaults =
                harness.policy().configuration();
        harness.correctionSequence++;
        assertSlowdown(harness.tick());

        for (int tick = 0;
             tick <= defaults.correctionWindowTicks();
             tick++) {
            harness.tick();
        }
        harness.correctionSequence++;

        MovementSafetyPolicy26.Decision next = harness.tick();
        assertSlowdown(next);
        assertEquals(
                MovementSafetyPolicy26.Reason.SERVER_CORRECTION,
                next.reason()
        );
    }

    @Test
    void appliedMotionReversalAndTeleportDiscontinuityAreDetected() {
        Harness reversal = warmedPolicy();
        reversal.policy().recordApplied(1.0, 0.0, 0.0);
        reversal.x = -0.1;
        MovementSafetyPolicy26.Decision reversed = reversal.tick();
        assertSlowdown(reversed);
        assertEquals(
                MovementSafetyPolicy26.Reason.MOTION_REVERSAL,
                reversed.reason()
        );

        Harness teleport = warmedPolicy();
        teleport.x =
                teleport.policy().configuration().teleportDistance() + 0.1;
        MovementSafetyPolicy26.Decision discontinuity = teleport.tick();
        assertPaused(discontinuity);
        assertEquals(
                MovementSafetyPolicy26.Reason.POSITION_DISCONTINUITY,
                discontinuity.reason()
        );
    }

    @Test
    void unusableAndNonFiniteObservationsResetAndFailClosed() {
        Harness harness = warmedPolicy();
        MovementSafetyPolicy26 policy = harness.policy();

        MovementSafetyPolicy26.Decision unusable = policy.observe(
                new MovementSafetyPolicy26.Observation(
                        harness.session(),
                        Double.NaN,
                        0.0,
                        0.0,
                        NORMAL_LATENCY_MILLIS,
                        false,
                        harness.correctionSequence,
                        FRESH_INBOUND_MILLIS
                )
        );
        assertPaused(unusable);
        assertEquals(MovementSafetyPolicy26.Reason.UNUSABLE, unusable.reason());
        assertFalse(policy.snapshot().sessionPresent());

        MovementSafetyPolicy26.Decision nonFinite = policy.observe(
                new MovementSafetyPolicy26.Observation(
                        harness.session(),
                        Double.POSITIVE_INFINITY,
                        0.0,
                        0.0,
                        NORMAL_LATENCY_MILLIS,
                        true,
                        harness.correctionSequence,
                        FRESH_INBOUND_MILLIS
                )
        );
        assertPaused(nonFinite);
        assertEquals(
                MovementSafetyPolicy26.Reason.UNUSABLE,
                nonFinite.reason()
        );

        assertPaused(observe(
                policy,
                harness.session(),
                0.0,
                harness.correctionSequence
        ));
        policy.reset();
        assertPaused(policy.decision());
        assertFalse(policy.snapshot().sessionPresent());
    }

    @Test
    void diagnosticsAndConfigurationValidateTheirBounds() {
        MovementSafetyPolicy26.Configuration defaults =
                MovementSafetyPolicy26.Configuration.defaults();
        assertEquals(0.45, defaults.slowdownScale());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementSafetyPolicy26.Configuration(
                        700,
                        350,
                        180,
                        1_500L,
                        5_000L,
                        2,
                        30,
                        40,
                        80,
                        5,
                        0.45,
                        6.0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementSafetyPolicy26.Observation(
                        new Object(),
                        0.0,
                        0.0,
                        0.0,
                        -2,
                        true,
                        0L,
                        0L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementSafetyPolicy26.Decision(
                        MovementSafetyPolicy26.State.ACTIVE,
                        MovementSafetyPolicy26.Reason.STABLE,
                        1.1,
                        0,
                        0,
                        0
                )
        );
    }

    private static Harness warmedPolicy() {
        MovementSafetyPolicy26 policy = new MovementSafetyPolicy26();
        Harness harness = new Harness(policy, new Object());
        assertPaused(harness.tick());
        assertPaused(harness.tick());
        assertActive(harness.tick());
        return harness;
    }

    private static void warm(
            MovementSafetyPolicy26 policy,
            Object session
    ) {
        assertPaused(observe(policy, session, 0.0, 0L));
        assertPaused(observe(policy, session, 0.0, 0L));
        assertActive(observe(policy, session, 0.0, 0L));
    }

    private static MovementSafetyPolicy26.Decision observe(
            MovementSafetyPolicy26 policy,
            Object session,
            double x,
            long correctionSequence
    ) {
        return policy.observe(new MovementSafetyPolicy26.Observation(
                session,
                x,
                0.0,
                0.0,
                NORMAL_LATENCY_MILLIS,
                true,
                correctionSequence,
                FRESH_INBOUND_MILLIS
        ));
    }

    private static MovementSafetyPolicy26.Decision observeNetwork(
            Harness harness,
            int latencyMillis,
            long inboundSilenceMillis
    ) {
        return harness.policy().observe(
                new MovementSafetyPolicy26.Observation(
                        harness.session(),
                        harness.x,
                        0.0,
                        0.0,
                        latencyMillis,
                        true,
                        harness.correctionSequence,
                        inboundSilenceMillis
                )
        );
    }

    private static void assertActive(MovementSafetyPolicy26.Decision decision) {
        assertEquals(MovementSafetyPolicy26.State.ACTIVE, decision.state());
        assertEquals(1.0, decision.scale());
        assertTrue(decision.canApply());
        assertTrue(decision.networkReady());
    }

    private static void assertSlowdown(
            MovementSafetyPolicy26.Decision decision
    ) {
        assertEquals(MovementSafetyPolicy26.State.SLOWDOWN, decision.state());
        assertEquals(
                MovementSafetyPolicy26.Configuration.defaults()
                        .slowdownScale(),
                decision.scale()
        );
        assertTrue(decision.canApply());
    }

    private static void assertPaused(MovementSafetyPolicy26.Decision decision) {
        assertEquals(MovementSafetyPolicy26.State.PAUSED, decision.state());
        assertEquals(0.0, decision.scale());
        assertFalse(decision.canApply());
        assertFalse(decision.networkReady());
    }

    private record EqualToken(int value) {
    }

    private static final class Harness {
        private final MovementSafetyPolicy26 policy;
        private final Object session;
        private double x;
        private long correctionSequence;

        private Harness(
                MovementSafetyPolicy26 policy,
                Object session
        ) {
            this.policy = policy;
            this.session = session;
        }

        MovementSafetyPolicy26.Decision tick() {
            return observe(policy, session, x, correctionSequence);
        }

        MovementSafetyPolicy26 policy() {
            return policy;
        }

        Object session() {
            return session;
        }
    }
}
