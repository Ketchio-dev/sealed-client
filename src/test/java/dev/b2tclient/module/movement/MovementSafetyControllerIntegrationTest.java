package dev.b2tclient.module.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementSafetyControllerIntegrationTest {
    private static final int NORMAL_LATENCY_MS = 50;

    @Test
    void freshAndReconnectedContextsPauseForTwoTicksAndDiscardCorrectionHistory() {
        MovementSafetyController controller = new MovementSafetyController();
        Object firstContext = new Object();

        assertPaused(observe(controller, firstContext, 0.0, NORMAL_LATENCY_MS));
        assertPaused(observe(controller, firstContext, 0.0, NORMAL_LATENCY_MS));
        assertActive(observe(controller, firstContext, 0.0, NORMAL_LATENCY_MS));

        controller.recordApplied(1.0, 0.0, 0.0);
        assertSlowdown(observe(controller, firstContext, -0.1, NORMAL_LATENCY_MS));

        Object reconnectedContext = new Object();
        assertPaused(observe(controller, reconnectedContext, 100.0, NORMAL_LATENCY_MS));
        assertPaused(observe(controller, reconnectedContext, 100.0, NORMAL_LATENCY_MS));
        assertActive(observe(controller, reconnectedContext, 100.0, NORMAL_LATENCY_MS));

        controller.recordApplied(1.0, 0.0, 0.0);
        assertSlowdown(observe(
                controller,
                reconnectedContext,
                99.9,
                NORMAL_LATENCY_MS
        ));
    }

    @Test
    void latencyThresholdsSelectActiveSlowdownAndPausedDecisions() {
        Harness harness = warmedController();
        MovementSafetyController controller = harness.controller();
        Object context = harness.context();

        assertActive(observe(
                controller,
                context,
                0.0,
                MovementSafetyController.HIGH_LATENCY_MS - 149
        ));
        assertActive(observe(
                controller,
                context,
                0.0,
                MovementSafetyController.HIGH_LATENCY_MS - 1
        ));
        assertSlowdown(observe(
                controller,
                context,
                0.0,
                MovementSafetyController.HIGH_LATENCY_MS
        ));
        assertSlowdown(observe(
                controller,
                context,
                0.0,
                MovementSafetyController.SEVERE_LATENCY_MS - 1
        ));
        assertPaused(observe(
                controller,
                context,
                0.0,
                MovementSafetyController.SEVERE_LATENCY_MS
        ));
    }

    @Test
    void repeatedReverseCorrectionsWithinWindowPauseForExactlyFortyTicks() {
        Harness harness = warmedController();
        MovementSafetyController controller = harness.controller();
        Object context = harness.context();

        controller.recordApplied(1.0, 0.0, 0.0);
        assertSlowdown(observe(controller, context, -0.1, NORMAL_LATENCY_MS));

        int neutralTicks = MovementSafetyController.CORRECTION_WINDOW_TICKS - 2;
        for (int tick = 0; tick < neutralTicks; tick++) {
            MovementSafetyController.Decision decision =
                    observe(controller, context, -0.1, NORMAL_LATENCY_MS);
            if (tick < MovementSafetyController.SLOWDOWN_TICKS - 1) {
                assertSlowdown(decision);
            } else {
                assertActive(decision);
            }
        }

        controller.recordApplied(1.0, 0.0, 0.0);
        assertPaused(observe(controller, context, -0.2, NORMAL_LATENCY_MS));

        for (int tick = 1; tick < MovementSafetyController.PAUSE_TICKS; tick++) {
            assertPaused(observe(controller, context, -0.2, NORMAL_LATENCY_MS));
        }
        assertActive(observe(controller, context, -0.2, NORMAL_LATENCY_MS));
    }

    @Test
    void resetIsImmediatelyActiveAndClearsThePositionBaseline() {
        Harness harness = warmedController();
        MovementSafetyController controller = harness.controller();
        Object context = harness.context();

        controller.recordApplied(1.0, 0.0, 0.0);
        assertSlowdown(observe(controller, context, -0.1, NORMAL_LATENCY_MS));

        controller.reset();
        assertEquals(MovementSafetyController.State.ACTIVE, controller.state());

        assertPaused(observe(controller, context, 1_000.0, NORMAL_LATENCY_MS));
        assertPaused(observe(controller, context, 1_000.0, NORMAL_LATENCY_MS));
        assertActive(observe(controller, context, 1_000.0, NORMAL_LATENCY_MS));
    }

    @Test
    void unusableObservationFailsClosedAndRequiresAFreshWarmup() {
        Harness harness = warmedController();
        MovementSafetyController controller = harness.controller();
        Object context = harness.context();

        controller.recordApplied(1.0, 0.0, 0.0);
        MovementSafetyController.Decision unusable = controller.observe(
                new MovementSafetyController.Observation(
                        context,
                        Double.NaN,
                        Double.NaN,
                        Double.NaN,
                        NORMAL_LATENCY_MS,
                        false
                )
        );
        assertPaused(unusable);
        assertEquals(MovementSafetyController.State.PAUSED, controller.state());

        assertPaused(observe(controller, context, 0.0, NORMAL_LATENCY_MS));
        assertPaused(observe(controller, context, 0.0, NORMAL_LATENCY_MS));
        assertActive(observe(controller, context, 0.0, NORMAL_LATENCY_MS));
    }

    private static Harness warmedController() {
        MovementSafetyController controller = new MovementSafetyController();
        Object context = new Object();
        assertPaused(observe(controller, context, 0.0, NORMAL_LATENCY_MS));
        assertPaused(observe(controller, context, 0.0, NORMAL_LATENCY_MS));
        assertActive(observe(controller, context, 0.0, NORMAL_LATENCY_MS));
        return new Harness(controller, context);
    }

    private static MovementSafetyController.Decision observe(
            MovementSafetyController controller,
            Object context,
            double x,
            int latencyMs
    ) {
        return controller.observe(new MovementSafetyController.Observation(
                context,
                x,
                0.0,
                0.0,
                latencyMs,
                true
        ));
    }

    private static void assertActive(MovementSafetyController.Decision decision) {
        assertEquals(MovementSafetyController.State.ACTIVE, decision.state());
        assertEquals(1.0, decision.scale());
        assertTrue(decision.canApply());
    }

    private static void assertSlowdown(MovementSafetyController.Decision decision) {
        assertEquals(MovementSafetyController.State.SLOWDOWN, decision.state());
        assertEquals(MovementSafetyController.SLOWDOWN_SCALE, decision.scale());
        assertTrue(decision.canApply());
    }

    private static void assertPaused(MovementSafetyController.Decision decision) {
        assertEquals(MovementSafetyController.State.PAUSED, decision.state());
        assertEquals(0.0, decision.scale());
        assertFalse(decision.canApply());
    }

    private record Harness(
            MovementSafetyController controller,
            Object context
    ) {
    }
}
