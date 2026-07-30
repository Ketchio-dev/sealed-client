package dev.sealedclient.v26.integration;

import java.util.Locale;
import java.util.Objects;

/**
 * Goal-API adapter. It never invokes Baritone's command system or writes text
 * to Minecraft chat.
 */
final class ReflectiveBaritoneNavigator26 implements BaritoneNavigator26 {
    private final String version;
    private final BaritoneAccess26 access;
    private final BaritoneNavigationLifecycle26 lifecycle;

    private Object ownedGoal;
    private boolean operational = true;
    private String failureDetail = "";
    private boolean cleanupPending;

    ReflectiveBaritoneNavigator26(
            String version,
            BaritoneAccess26 access,
            Limits limits
    ) {
        this.version = Objects.requireNonNullElse(version, "");
        this.access = Objects.requireNonNull(access, "access");
        lifecycle = new BaritoneNavigationLifecycle26(
                Objects.requireNonNull(limits, "limits")
        );
    }

    @Override
    public synchronized boolean available() {
        return operational;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public synchronized NavigationResult goTo(int x, int y, int z) {
        if (!operational) {
            return unavailableResult();
        }
        try {
            if (cleanupPending && !finishPendingCleanup()) {
                return NavigationResult.failure(
                        "The previous Sealed path is still finishing an "
                                + "uncancelable movement"
                );
            }
            BaritoneAccess26.Observation observation = access.observe();
            if (observation.hasExternalGoal(ownedGoal)) {
                if (lifecycle.owned()) {
                    lifecycle.cancel(
                            "Sealed ownership released; another Baritone goal is active"
                    );
                }
                return NavigationResult.failure(
                        "Another Baritone goal is active; Sealed did not replace it"
                );
            }
            if (!lifecycle.owned()
                    && observation.exactGoalPresent(ownedGoal)
                    && (observation.pathing() || observation.planning())) {
                return NavigationResult.failure(
                        "The previous Sealed path has not stopped yet"
                );
            }

            if (lifecycle.owned()
                    && !lifecycle.paused()
                    && observation.exactGoalPresent(ownedGoal)
                    && !access.cancelEverything()) {
                cleanupPending = true;
                lifecycle.cancel(
                        "Could not replace Sealed path during an uncancelable movement"
                );
                return NavigationResult.failure(
                        lifecycle.snapshot().detail()
                );
            }

            Object goal = access.createGoal(x, y, z);
            NavigationTarget target = new NavigationTarget(x, y, z);
            ownedGoal = goal;
            lifecycle.start(
                    target,
                    distanceSquared(observation.playerFeet(), target)
            );
            try {
                access.setGoalAndPath(goal);
                cleanupPending = false;
            } catch (RuntimeException | LinkageError exception) {
                throw exception;
            }
            return NavigationResult.success(String.format(
                    Locale.ROOT,
                    "Navigating to %d, %d, %d",
                    x,
                    y,
                    z
            ));
        } catch (RuntimeException | LinkageError exception) {
            return fail("Could not start Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationResult pause() {
        if (!operational) {
            return unavailableResult();
        }
        if (!lifecycle.owned()) {
            return NavigationResult.failure(
                    "No Sealed-owned navigation to pause"
            );
        }
        if (lifecycle.paused()) {
            return NavigationResult.success(
                    "Sealed navigation is already paused"
            );
        }
        try {
            BaritoneAccess26.Observation observation = access.observe();
            if (!observation.exactGoalPresent(ownedGoal)) {
                lifecycle.cancel(
                        "Cannot pause: Baritone goal changed outside Sealed Client"
                );
                cleanupPending = false;
                return NavigationResult.failure(
                        lifecycle.snapshot().detail()
                );
            }
            boolean fullyCancelled = access.cancelEverything();
            if (!fullyCancelled) {
                cleanupPending = true;
                lifecycle.cancel(
                        "Pause could not finish during an uncancelable movement"
                );
                return NavigationResult.failure(
                        lifecycle.snapshot().detail()
                );
            }
            cleanupPending = false;
            if (!lifecycle.pause()) {
                lifecycle.cancel(
                        "Sealed path was canceled instead of paused"
                );
                return NavigationResult.failure(
                        lifecycle.snapshot().detail()
                );
            }
            return NavigationResult.success("Sealed navigation paused");
        } catch (RuntimeException | LinkageError exception) {
            return fail("Could not pause Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationResult resume() {
        if (!operational) {
            return unavailableResult();
        }
        if (!lifecycle.paused()) {
            return NavigationResult.failure(
                    "No paused Sealed navigation to resume"
            );
        }
        try {
            BaritoneAccess26.Observation observation = access.observe();
            if (observation.hasExternalGoal(ownedGoal)) {
                lifecycle.cancel(
                        "Cannot resume: another Baritone goal is active"
                );
                return NavigationResult.failure(
                        lifecycle.snapshot().detail()
                );
            }
            if (!lifecycle.resume()) {
                return NavigationResult.failure(
                        "Sealed navigation cannot be resumed now"
                );
            }
            access.setGoalAndPath(ownedGoal);
            cleanupPending = false;
            return NavigationResult.success("Sealed navigation resumed");
        } catch (RuntimeException | LinkageError exception) {
            return fail("Could not resume Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationResult stop() {
        if (!operational) {
            return stopAfterFailure();
        }
        if (!lifecycle.owned()) {
            if (cleanupPending) {
                if (finishPendingCleanup()) {
                    return NavigationResult.success(
                            "Sealed-owned navigation cancellation completed"
                    );
                }
                return NavigationResult.failure(
                        "Sealed path is still finishing an uncancelable movement"
                );
            }
            return NavigationResult.success(
                    "No Sealed-owned navigation is active"
            );
        }
        try {
            if (lifecycle.paused()) {
                lifecycle.cancel("Sealed navigation stopped");
                cleanupPending = false;
                return NavigationResult.success("Sealed navigation stopped");
            }

            BaritoneAccess26.Observation observation = access.observe();
            if (!observation.exactGoalPresent(ownedGoal)) {
                lifecycle.cancel(
                        "Sealed ownership released; another Baritone goal is active"
                );
                cleanupPending = false;
                return NavigationResult.success(
                        lifecycle.snapshot().detail()
                );
            }
            boolean fullyCancelled = access.cancelEverything();
            cleanupPending = !fullyCancelled;
            lifecycle.cancel(fullyCancelled
                    ? "Sealed navigation stopped"
                    : "Sealed navigation is finishing an uncancelable movement");
            return fullyCancelled
                    ? NavigationResult.success("Sealed navigation stopped")
                    : NavigationResult.failure(
                            lifecycle.snapshot().detail()
                    );
        } catch (RuntimeException | LinkageError exception) {
            return fail("Could not stop Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationStatus status() {
        // Provider sampling belongs exclusively to tick(). Keeping status()
        // pure prevents HUD, commands, and runtime arbitration from sampling
        // the reflective API several times in one client tick.
        return lifecycle.snapshot();
    }

    @Override
    public synchronized boolean movementReserved() {
        return cleanupPending
                || (lifecycle.owned() && !lifecycle.paused());
    }

    @Override
    public synchronized void tick() {
        if (cleanupPending && !lifecycle.owned()) {
            finishPendingCleanup();
            return;
        }
        if (!operational
                || !lifecycle.owned()
                || lifecycle.paused()
                || ownedGoal == null) {
            return;
        }
        try {
            BaritoneAccess26.Observation observation = access.observe();
            boolean goalMatches = goalMatchesForState(observation);
            BaritoneAccess26.Position feet = observation.playerFeet();
            boolean atGoal = feet != null
                    && access.isInGoal(ownedGoal, feet);
            NavigationTarget target = lifecycle.snapshot().target();
            BaritoneNavigationLifecycle26.Directive directive =
                    lifecycle.observe(
                            distanceSquared(feet, target),
                            atGoal,
                            observation.pathing(),
                            observation.planning(),
                            goalMatches
                    );

            if (directive
                    == BaritoneNavigationLifecycle26.Directive.RETRY) {
                BaritoneAccess26.Observation retryObservation =
                        access.observe();
                if (retryObservation.hasExternalGoal(ownedGoal)) {
                    lifecycle.cancel(
                            "Retry canceled: another Baritone goal is active"
                    );
                    cleanupPending = false;
                    return;
                }
                access.setGoalAndPath(ownedGoal);
                cleanupPending = false;
            } else if (directive
                    == BaritoneNavigationLifecycle26.Directive.CANCEL_OWNED
                    && observation.exactGoalPresent(ownedGoal)) {
                cleanupPending = !access.cancelEverything();
            }
            if (!lifecycle.owned()) {
                if (lifecycle.state() == NavigationState.COMPLETED
                        || lifecycle.state() == NavigationState.IDLE) {
                    cleanupPending = false;
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            fail("Baritone lifecycle tick failed", exception);
        }
    }

    @Override
    public synchronized void releaseOwnedNavigation() {
        if (lifecycle.owned() || cleanupPending) {
            stop();
        }
    }

    @Override
    public synchronized void resetSession() {
        if ((lifecycle.owned() && !lifecycle.paused()) || cleanupPending) {
            CleanupOutcome outcome = cleanupOwnedBestEffort(ownedGoal);
            cleanupPending = outcome == CleanupOutcome.PENDING
                    || outcome == CleanupOutcome.FAILED;
        }
        if (operational) {
            lifecycle.reset();
        } else {
            lifecycle.error(failureDetail);
        }
        if (!cleanupPending) {
            ownedGoal = null;
        }
    }

    private NavigationResult fail(String prefix, Throwable throwable) {
        if (ownedGoal != null && (lifecycle.owned() || cleanupPending)) {
            CleanupOutcome outcome = cleanupOwnedBestEffort(ownedGoal);
            cleanupPending = outcome == CleanupOutcome.PENDING
                    || outcome == CleanupOutcome.FAILED;
        }
        operational = false;
        failureDetail = errorDetail(prefix, throwable);
        lifecycle.error(failureDetail);
        if (!cleanupPending) {
            ownedGoal = null;
        }
        return NavigationResult.failure(lifecycle.snapshot().detail());
    }

    private NavigationResult unavailableResult() {
        return NavigationResult.failure(lifecycle.snapshot().detail());
    }

    private boolean goalMatchesForState(
            BaritoneAccess26.Observation observation
    ) {
        if (lifecycle.state() == NavigationState.RETRYING) {
            return !observation.hasDistinctExternalGoal(ownedGoal);
        }
        return observation.exactGoalPresent(ownedGoal);
    }

    private NavigationResult stopAfterFailure() {
        if (!cleanupPending || ownedGoal == null) {
            return unavailableResult();
        }
        if (finishPendingCleanup()) {
            return NavigationResult.success(
                    "Sealed-owned navigation cancellation completed after "
                            + "integration failure"
            );
        }
        return NavigationResult.failure(
                failureDetail + "; owned path cancellation is still pending"
        );
    }

    private boolean finishPendingCleanup() {
        CleanupOutcome outcome = cleanupOwnedBestEffort(ownedGoal);
        cleanupPending = outcome == CleanupOutcome.PENDING
                || outcome == CleanupOutcome.FAILED;
        if (!cleanupPending) {
            ownedGoal = null;
        }
        return !cleanupPending;
    }

    private CleanupOutcome cleanupOwnedBestEffort(Object expectedGoal) {
        if (expectedGoal == null) {
            return CleanupOutcome.QUIESCENT;
        }
        try {
            BaritoneAccess26.Observation observation = access.observe();
            if (observation.hasDistinctExternalGoal(expectedGoal)) {
                return CleanupOutcome.EXTERNAL;
            }
            if (observation.exactGoalPresent(expectedGoal)) {
                return access.cancelEverything()
                        ? CleanupOutcome.CANCELLED
                        : CleanupOutcome.PENDING;
            }
            if (observation.quiescent()) {
                return CleanupOutcome.QUIESCENT;
            }
            return CleanupOutcome.PENDING;
        } catch (RuntimeException | LinkageError ignored) {
            // A blind cancel could disturb navigation owned by another mod.
            return CleanupOutcome.FAILED;
        }
    }

    private enum CleanupOutcome {
        CANCELLED,
        QUIESCENT,
        EXTERNAL,
        PENDING,
        FAILED
    }

    private static double distanceSquared(
            BaritoneAccess26.Position position,
            NavigationTarget target
    ) {
        if (position == null || target == null) {
            return Double.POSITIVE_INFINITY;
        }
        double x = (double) position.x() - target.x();
        double y = (double) position.y() - target.y();
        double z = (double) position.z() - target.z();
        return x * x + y * y + z * z;
    }

    private static String errorDetail(String prefix, Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return prefix + " (" + root.getClass().getSimpleName() + ")";
    }
}
