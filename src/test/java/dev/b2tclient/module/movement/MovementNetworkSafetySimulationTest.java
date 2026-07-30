package dev.b2tclient.module.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementNetworkSafetySimulationTest {
    private static final int NORMAL_LATENCY_MS = 50;
    private static final long FRESH_INBOUND_MS = 20L;

    @Test
    void stableLatencyAndFreshTrafficRemainActive() {
        Simulation simulation = new Simulation();
        simulation.warmup(NORMAL_LATENCY_MS, FRESH_INBOUND_MS);

        int[] stableSamples = {45, 58, 51, 62, 48, 55, 50};
        for (int latency : stableSamples) {
            assertState(
                    MovementSafetyController.State.ACTIVE,
                    simulation.tick(latency, FRESH_INBOUND_MS)
            );
        }
    }

    @Test
    void jitterBurstSlowsThenRequiresFiveStableRecoveryTicks() {
        Simulation simulation = new Simulation();
        simulation.warmup(NORMAL_LATENCY_MS, FRESH_INBOUND_MS);

        assertState(
                MovementSafetyController.State.SLOWDOWN,
                simulation.tick(
                        NORMAL_LATENCY_MS + MovementSafetyController.JITTER_BURST_MS,
                        FRESH_INBOUND_MS
                )
        );
        assertState(
                MovementSafetyController.State.SLOWDOWN,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );

        for (int tick = 0; tick < MovementSafetyController.NETWORK_RECOVERY_TICKS; tick++) {
            assertState(
                    MovementSafetyController.State.SLOWDOWN,
                    simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
            );
        }
        assertState(
                MovementSafetyController.State.ACTIVE,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
    }

    @Test
    void staleAndTimedOutInboundTrafficFailClosedWithHysteresis() {
        Simulation simulation = new Simulation();
        simulation.warmup(NORMAL_LATENCY_MS, FRESH_INBOUND_MS);

        assertState(
                MovementSafetyController.State.SLOWDOWN,
                simulation.tick(
                        NORMAL_LATENCY_MS,
                        MovementSafetyController.STALE_INBOUND_MS
                )
        );
        assertState(
                MovementSafetyController.State.PAUSED,
                simulation.tick(
                        NORMAL_LATENCY_MS,
                        MovementSafetyController.TIMED_OUT_INBOUND_MS
                )
        );

        for (int tick = 0; tick < MovementSafetyController.NETWORK_RECOVERY_TICKS; tick++) {
            assertState(
                    MovementSafetyController.State.PAUSED,
                    simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
            );
        }
        assertState(
                MovementSafetyController.State.ACTIVE,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
    }

    @Test
    void oneExplicitCorrectionSlowsWithoutDependingOnMotionInference() {
        Simulation simulation = new Simulation();
        simulation.warmup(NORMAL_LATENCY_MS, FRESH_INBOUND_MS);

        simulation.correctionSequence++;
        assertState(
                MovementSafetyController.State.SLOWDOWN,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );

        for (int tick = 1; tick < MovementSafetyController.SLOWDOWN_TICKS; tick++) {
            assertState(
                    MovementSafetyController.State.SLOWDOWN,
                    simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
            );
        }
        assertState(
                MovementSafetyController.State.ACTIVE,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
    }

    @Test
    void repeatedExplicitCorrectionsPauseAndRecover() {
        Simulation simulation = new Simulation();
        simulation.warmup(NORMAL_LATENCY_MS, FRESH_INBOUND_MS);

        simulation.correctionSequence++;
        assertState(
                MovementSafetyController.State.SLOWDOWN,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
        simulation.correctionSequence++;
        assertState(
                MovementSafetyController.State.PAUSED,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );

        for (int tick = 1; tick < MovementSafetyController.PAUSE_TICKS; tick++) {
            assertState(
                    MovementSafetyController.State.PAUSED,
                    simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
            );
        }
        assertState(
                MovementSafetyController.State.ACTIVE,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
    }

    @Test
    void multipleCorrectionsBetweenTicksAreTreatedAsSevere() {
        Simulation simulation = new Simulation();
        simulation.warmup(NORMAL_LATENCY_MS, FRESH_INBOUND_MS);

        simulation.correctionSequence += 2L;
        assertState(
                MovementSafetyController.State.PAUSED,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
    }

    @Test
    void teleportDiscontinuityPausesEvenWithoutAPacketSignal() {
        Simulation simulation = new Simulation();
        simulation.warmup(NORMAL_LATENCY_MS, FRESH_INBOUND_MS);

        simulation.x = 7.0;
        assertState(
                MovementSafetyController.State.PAUSED,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
        for (int tick = 1; tick < MovementSafetyController.PAUSE_TICKS; tick++) {
            assertState(
                    MovementSafetyController.State.PAUSED,
                    simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
            );
        }
        assertState(
                MovementSafetyController.State.ACTIVE,
                simulation.tick(NORMAL_LATENCY_MS, FRESH_INBOUND_MS)
        );
    }

    private static void assertState(
            MovementSafetyController.State expected,
            MovementSafetyController.Decision decision
    ) {
        assertEquals(expected, decision.state());
    }

    private static final class Simulation {
        private final MovementSafetyController controller =
                new MovementSafetyController();
        private final Object context = new Object();
        private double x;
        private long correctionSequence;

        void warmup(int latencyMs, long inboundSilenceMs) {
            assertState(
                    MovementSafetyController.State.PAUSED,
                    tick(latencyMs, inboundSilenceMs)
            );
            assertState(
                    MovementSafetyController.State.PAUSED,
                    tick(latencyMs, inboundSilenceMs)
            );
            assertState(
                    MovementSafetyController.State.ACTIVE,
                    tick(latencyMs, inboundSilenceMs)
            );
        }

        MovementSafetyController.Decision tick(
                int latencyMs,
                long inboundSilenceMs
        ) {
            return controller.observe(new MovementSafetyController.Observation(
                    context,
                    x,
                    0.0,
                    0.0,
                    latencyMs,
                    true,
                    correctionSequence,
                    inboundSilenceMs
            ));
        }
    }
}
