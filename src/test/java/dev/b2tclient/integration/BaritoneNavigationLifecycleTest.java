package dev.b2tclient.integration;

import org.junit.jupiter.api.Test;

import static dev.b2tclient.integration.BaritoneNavigationLifecycle.Directive.CANCEL_OWNED;
import static dev.b2tclient.integration.BaritoneNavigationLifecycle.Directive.NONE;
import static dev.b2tclient.integration.BaritoneNavigationLifecycle.Directive.RETRY;
import static dev.b2tclient.integration.BaritoneNavigationLifecycle.PathSignal.AT_GOAL;
import static dev.b2tclient.integration.BaritoneNavigationLifecycle.PathSignal.CALC_FAILED;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.CANCELLED;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.COMPLETED;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.FAILED;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.IDLE;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.PATHING;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.PAUSED;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.PLANNING;
import static dev.b2tclient.integration.BaritoneNavigator.NavigationState.RETRYING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneNavigationLifecycleTest {
    private static final BaritoneNavigator.NavigationTarget TARGET =
            new BaritoneNavigator.NavigationTarget(12, 64, -4);

    @Test
    void reportsPlanningPathProgressAndArrival() {
        BaritoneNavigationLifecycle lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        assertStatus(lifecycle, PLANNING, true, 0);
        lifecycle.pathSignal(BaritoneNavigationLifecycle.PathSignal.PATHING);
        assertEquals(NONE, lifecycle.observe(81D, false, true, false, true));
        assertStatus(lifecycle, PATHING, true, 0);
        assertTrue(lifecycle.snapshot().detail().contains("9.0 blocks"));

        lifecycle.pathSignal(AT_GOAL);
        assertStatus(lifecycle, COMPLETED, false, 0);
        assertEquals(TARGET, lifecycle.snapshot().target());
        assertTrue(lifecycle.snapshot().detail().contains("Arrived"));
    }

    @Test
    void retriesCalculationFailureWithBoundedBackoffThenFailsClosed() {
        BaritoneNavigationLifecycle lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        lifecycle.pathSignal(CALC_FAILED);
        assertStatus(lifecycle, RETRYING, true, 0);
        assertEquals(NONE, lifecycle.observe(100D, false, false, false, true));
        assertEquals(RETRY, lifecycle.observe(100D, false, false, false, true));
        assertStatus(lifecycle, PLANNING, true, 1);

        lifecycle.pathSignal(CALC_FAILED);
        lifecycle.observe(100D, false, false, false, true);
        assertEquals(RETRY, lifecycle.observe(100D, false, false, false, true));
        assertStatus(lifecycle, PLANNING, true, 2);

        lifecycle.pathSignal(CALC_FAILED);
        assertStatus(lifecycle, FAILED, false, 2);
        assertTrue(lifecycle.snapshot().detail().contains("retry limit"));
    }

    @Test
    void noDistanceProgressTriggersCancelAndRetryDirective() {
        BaritoneNavigationLifecycle lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        assertEquals(NONE, lifecycle.observe(100D, false, true, false, true));
        assertEquals(NONE, lifecycle.observe(100D, false, true, false, true));
        assertEquals(CANCEL_OWNED, lifecycle.observe(
                100D,
                false,
                true,
                false,
                true
        ));
        assertStatus(lifecycle, RETRYING, true, 0);
        assertEquals(NONE, lifecycle.observe(100D, false, false, false, true));
        assertEquals(RETRY, lifecycle.observe(100D, false, false, false, true));
    }

    @Test
    void pauseFreezesTimeoutAndResumeRestartsSameOwnedTarget() {
        BaritoneNavigationLifecycle lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);
        lifecycle.observe(81D, false, true, false, true);

        assertTrue(lifecycle.pause());
        assertStatus(lifecycle, PAUSED, true, 0);
        long elapsedAtPause = lifecycle.snapshot().elapsedTicks();
        for (int index = 0; index < 20; index++) {
            assertEquals(NONE, lifecycle.observe(
                    81D,
                    false,
                    false,
                    false,
                    true
            ));
        }
        assertEquals(elapsedAtPause, lifecycle.snapshot().elapsedTicks());

        assertTrue(lifecycle.resume());
        assertStatus(lifecycle, PLANNING, true, 0);
        assertEquals(NONE, lifecycle.observe(64D, false, true, false, true));
        assertEquals(TARGET, lifecycle.snapshot().target());
    }

    @Test
    void externalGoalChangeReleasesOwnershipWithoutCancellation() {
        BaritoneNavigationLifecycle lifecycle = lifecycle();
        lifecycle.start(TARGET, 100D);

        assertEquals(NONE, lifecycle.observe(
                100D,
                false,
                true,
                false,
                false
        ));
        assertStatus(lifecycle, IDLE, false, 0);
        assertTrue(lifecycle.snapshot().detail().contains("outside B2T"));
    }

    @Test
    void explicitCancelAndSessionResetHaveDistinctDiagnostics() {
        BaritoneNavigationLifecycle lifecycle = lifecycle();
        lifecycle.start(TARGET, 4D);
        lifecycle.cancel("operator stop");
        assertStatus(lifecycle, CANCELLED, false, 0);
        assertEquals(TARGET, lifecycle.snapshot().target());

        lifecycle.reset();
        assertStatus(lifecycle, IDLE, false, 0);
        assertEquals(null, lifecycle.snapshot().target());
        assertEquals(0L, lifecycle.snapshot().elapsedTicks());
    }

    private static BaritoneNavigationLifecycle lifecycle() {
        return new BaritoneNavigationLifecycle(2, 2L, 3L, 30L);
    }

    private static void assertStatus(
            BaritoneNavigationLifecycle lifecycle,
            BaritoneNavigator.NavigationState state,
            boolean owned,
            int retries
    ) {
        BaritoneNavigator.NavigationStatus status = lifecycle.snapshot();
        assertEquals(state, status.state());
        assertEquals(owned, status.ownedByB2T());
        assertEquals(retries, status.retryCount());
    }
}
