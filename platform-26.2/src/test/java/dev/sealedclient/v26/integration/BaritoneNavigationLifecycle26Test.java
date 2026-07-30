package dev.sealedclient.v26.integration;

import org.junit.jupiter.api.Test;

import static dev.sealedclient.v26.integration.BaritoneNavigationLifecycle26.Directive.CANCEL_OWNED;
import static dev.sealedclient.v26.integration.BaritoneNavigationLifecycle26.Directive.NONE;
import static dev.sealedclient.v26.integration.BaritoneNavigationLifecycle26.Directive.RETRY;
import static dev.sealedclient.v26.integration.BaritoneNavigationLifecycle26.PathSignal.AT_GOAL;
import static dev.sealedclient.v26.integration.BaritoneNavigationLifecycle26.PathSignal.CALC_FAILED;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.COMPLETED;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.FAILED;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.IDLE;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.PATHING;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.PAUSED;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.PLANNING;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.RETRYING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneNavigationLifecycle26Test {
    private static final BaritoneNavigator26.NavigationTarget TARGET =
            new BaritoneNavigator26.NavigationTarget(12, 64, -4);

    @Test
    void reportsPlanningProgressAndArrival() {
        BaritoneNavigationLifecycle26 lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        assertStatus(lifecycle, PLANNING, true, 0);
        lifecycle.pathSignal(
                BaritoneNavigationLifecycle26.PathSignal.PATHING
        );
        assertEquals(
                NONE,
                lifecycle.observe(81D, false, true, false, true)
        );
        assertStatus(lifecycle, PATHING, true, 0);
        assertTrue(lifecycle.snapshot().detail().contains("9.0 blocks"));

        lifecycle.pathSignal(AT_GOAL);
        assertStatus(lifecycle, COMPLETED, false, 0);
        assertEquals(TARGET, lifecycle.snapshot().target());
    }

    @Test
    void calculationFailuresRetryWithBoundedBackoffThenFailClosed() {
        BaritoneNavigationLifecycle26 lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        lifecycle.pathSignal(CALC_FAILED);
        assertStatus(lifecycle, RETRYING, true, 0);
        assertEquals(
                NONE,
                lifecycle.observe(100D, false, false, false, true)
        );
        assertEquals(
                RETRY,
                lifecycle.observe(100D, false, false, false, true)
        );
        assertStatus(lifecycle, PLANNING, true, 1);

        lifecycle.pathSignal(CALC_FAILED);
        lifecycle.observe(100D, false, false, false, true);
        assertEquals(
                RETRY,
                lifecycle.observe(100D, false, false, false, true)
        );
        assertStatus(lifecycle, PLANNING, true, 2);

        lifecycle.pathSignal(CALC_FAILED);
        assertStatus(lifecycle, FAILED, false, 2);
        assertTrue(lifecycle.snapshot().detail().contains("retry limit"));
    }

    @Test
    void stalledPathIsCancelledBeforeItsBoundedRetry() {
        BaritoneNavigationLifecycle26 lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        lifecycle.observe(100D, false, true, false, true);
        assertEquals(
                CANCEL_OWNED,
                lifecycle.observe(100D, false, true, false, true)
        );
        assertStatus(lifecycle, RETRYING, true, 0);
        lifecycle.observe(100D, false, false, false, true);
        assertEquals(
                RETRY,
                lifecycle.observe(100D, false, false, false, true)
        );
    }

    @Test
    void overallTimeoutCancelsAndDropsOwnership() {
        BaritoneNavigationLifecycle26 lifecycle =
                new BaritoneNavigationLifecycle26(
                        new BaritoneNavigator26.Limits(2, 1L, 3L, 3L)
                );
        lifecycle.start(TARGET, 100D);

        assertEquals(
                NONE,
                lifecycle.observe(100D, false, true, false, true)
        );
        assertEquals(
                NONE,
                lifecycle.observe(100D, false, true, false, true)
        );
        assertEquals(
                CANCEL_OWNED,
                lifecycle.observe(100D, false, true, false, true)
        );
        assertStatus(lifecycle, FAILED, false, 0);
        assertTrue(lifecycle.snapshot().detail().contains("timed out"));
    }

    @Test
    void externalGoalDropsOwnershipWithoutRequestingCancellation() {
        BaritoneNavigationLifecycle26 lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        assertEquals(
                NONE,
                lifecycle.observe(100D, false, true, false, false)
        );
        assertStatus(lifecycle, IDLE, false, 0);
        assertTrue(lifecycle.snapshot().detail().contains("outside Sealed Client"));
    }

    @Test
    void pauseFreezesElapsedTimeAndResumeRestartsPlanning() {
        BaritoneNavigationLifecycle26 lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);
        lifecycle.observe(90D, false, true, false, true);
        assertTrue(lifecycle.pause());
        long pausedElapsed = lifecycle.snapshot().elapsedTicks();

        for (int tick = 0; tick < 20; tick++) {
            assertEquals(
                    NONE,
                    lifecycle.observe(90D, false, false, false, false)
            );
        }
        assertStatus(lifecycle, PAUSED, true, 0);
        assertEquals(pausedElapsed, lifecycle.snapshot().elapsedTicks());

        assertTrue(lifecycle.resume());
        assertStatus(lifecycle, PLANNING, true, 0);
        assertFalse(lifecycle.paused());
    }

    @Test
    void resetRemovesEveryTransientOwnershipField() {
        BaritoneNavigationLifecycle26 lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);
        lifecycle.pathSignal(CALC_FAILED);
        lifecycle.reset();

        assertStatus(lifecycle, IDLE, false, 0);
        assertEquals(null, lifecycle.snapshot().target());
        assertEquals(0L, lifecycle.snapshot().elapsedTicks());
    }

    private static BaritoneNavigationLifecycle26 lifecycle() {
        return new BaritoneNavigationLifecycle26(
                new BaritoneNavigator26.Limits(2, 2L, 2L, 20L)
        );
    }

    private static void assertStatus(
            BaritoneNavigationLifecycle26 lifecycle,
            BaritoneNavigator26.NavigationState state,
            boolean owned,
            int retries
    ) {
        BaritoneNavigator26.NavigationStatus status = lifecycle.snapshot();
        assertEquals(state, status.state());
        assertEquals(owned, status.ownedBySealed());
        assertEquals(retries, status.retryCount());
    }
}
