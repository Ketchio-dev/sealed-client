package dev.sealedclient.integration;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Locale;
import java.util.Objects;

/**
 * The only production class linked to Baritone. It is instantiated only after
 * Fabric confirms that the separately installed {@code baritone} mod exists.
 */
final class BaritoneApiNavigator implements BaritoneNavigator {
    private final String version;
    private final BaritoneNavigationLifecycle lifecycle =
            new BaritoneNavigationLifecycle();
    private final AbstractGameEventListener eventListener =
            new AbstractGameEventListener() {
                @Override
                public void onTick(TickEvent event) {
                    // Lifecycle sampling runs after Baritone's own tick below.
                }

                @Override
                public void onPostTick(TickEvent event) {
                    handleTick();
                }

                @Override
                public void onPathEvent(PathEvent event) {
                    handlePathEvent(event);
                }

                @Override
                public void onWorldEvent(WorldEvent event) {
                    handleWorldChange();
                }
            };

    private GoalBlock ownedGoal;
    private IBaritone registeredBaritone;

    BaritoneApiNavigator(String version) {
        this.version = Objects.requireNonNullElse(version, "");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public synchronized NavigationResult goTo(int x, int y, int z) {
        try {
            IBaritone baritone = primaryAndRegister();
            GoalBlock goal = new GoalBlock(x, y, z);
            BaritoneNavigator.NavigationTarget target =
                    new BaritoneNavigator.NavigationTarget(x, y, z);
            lifecycle.start(target, distanceSquared(baritone, goal));
            ownedGoal = goal;
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            return NavigationResult.success(String.format(
                    Locale.ROOT,
                    "Navigating to %d, %d, %d",
                    x,
                    y,
                    z
            ));
        } catch (LinkageError | RuntimeException exception) {
            ownedGoal = null;
            lifecycle.error(errorDetail("Could not start Baritone", exception));
            return failure("Could not start Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationResult pause() {
        if (!lifecycle.owned()) {
            return NavigationResult.failure("No Sealed-owned navigation to pause");
        }
        if (lifecycle.paused()) {
            return NavigationResult.success("Sealed navigation is already paused");
        }
        try {
            IBaritone baritone = primaryAndRegister();
            if (!goalStillOwned(baritone)) {
                lifecycle.cancel("Cannot pause: Baritone goal changed outside Sealed Client");
                return NavigationResult.failure(lifecycle.snapshot().detail());
            }
            if (!lifecycle.pause()) {
                return NavigationResult.failure("Sealed navigation cannot be paused now");
            }
            baritone.getPathingBehavior().cancelEverything();
            return NavigationResult.success("Sealed navigation paused");
        } catch (LinkageError | RuntimeException exception) {
            lifecycle.error(errorDetail("Could not pause Baritone", exception));
            return failure("Could not pause Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationResult resume() {
        if (!lifecycle.paused()) {
            return NavigationResult.failure("No paused Sealed navigation to resume");
        }
        try {
            IBaritone baritone = primaryAndRegister();
            Goal currentGoal = baritone.getPathingBehavior().getGoal();
            if (currentGoal != null && !ownedGoal.equals(currentGoal)) {
                lifecycle.cancel("Cannot resume: another Baritone goal is active");
                return NavigationResult.failure(lifecycle.snapshot().detail());
            }
            if (!lifecycle.resume()) {
                return NavigationResult.failure("Sealed navigation cannot be resumed now");
            }
            baritone.getCustomGoalProcess().setGoalAndPath(ownedGoal);
            return NavigationResult.success("Sealed navigation resumed");
        } catch (LinkageError | RuntimeException exception) {
            lifecycle.error(errorDetail("Could not resume Baritone", exception));
            return failure("Could not resume Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationResult stop() {
        if (!lifecycle.owned()) {
            return NavigationResult.success("No Sealed-owned navigation is active");
        }
        try {
            IBaritone baritone = primaryAndRegister();
            boolean wasPaused = lifecycle.paused();
            if (!wasPaused && !goalStillOwned(baritone)) {
                lifecycle.cancel("Sealed ownership released; another Baritone goal is active");
                return NavigationResult.success(lifecycle.snapshot().detail());
            }
            lifecycle.cancel("Sealed navigation stopped");
            if (!wasPaused) {
                baritone.getPathingBehavior().cancelEverything();
            }
            return NavigationResult.success("Sealed navigation stopped");
        } catch (LinkageError | RuntimeException exception) {
            lifecycle.error(errorDetail("Could not stop Baritone", exception));
            return failure("Could not stop Baritone", exception);
        }
    }

    @Override
    public synchronized NavigationStatus status() {
        try {
            IBaritone baritone = primaryAndRegister();
            reconcileOwnership(baritone);
            return lifecycle.snapshot();
        } catch (LinkageError | RuntimeException exception) {
            lifecycle.error(errorDetail("Baritone status failed", exception));
            return lifecycle.snapshot();
        }
    }

    @Override
    public synchronized void releaseOwnedNavigation() {
        if (!lifecycle.owned()) {
            return;
        }
        stop();
    }

    @Override
    public synchronized void resetSession() {
        if (lifecycle.owned()) {
            try {
                IBaritone baritone = primaryAndRegister();
                if (!lifecycle.paused() && goalStillOwned(baritone)) {
                    lifecycle.cancel("Navigation session reset");
                    baritone.getPathingBehavior().cancelEverything();
                }
            } catch (LinkageError | RuntimeException ignored) {
                // World teardown can make the optional provider unavailable.
            }
        }
        lifecycle.reset();
        ownedGoal = null;
    }

    private synchronized void handleTick() {
        if (!lifecycle.owned() || registeredBaritone == null || ownedGoal == null) {
            return;
        }
        try {
            IBaritone baritone = registeredBaritone;
            IPathingBehavior pathing = baritone.getPathingBehavior();
            BlockPos feet = playerFeet();
            boolean atGoal = feet != null
                    && ownedGoal.isInGoal(feet.getX(), feet.getY(), feet.getZ());
            boolean goalMatches = goalStillOwned(baritone);
            boolean planning = baritone.getCustomGoalProcess().isActive()
                    || pathing.getInProgress().isPresent();
            BaritoneNavigationLifecycle.Directive directive = lifecycle.observe(
                    distanceSquared(feet, ownedGoal),
                    atGoal,
                    pathing.isPathing(),
                    planning,
                    goalMatches
            );
            if (directive == BaritoneNavigationLifecycle.Directive.RETRY) {
                baritone.getCustomGoalProcess().setGoalAndPath(ownedGoal);
            } else if (directive
                    == BaritoneNavigationLifecycle.Directive.CANCEL_OWNED
                    && goalMatches) {
                pathing.cancelEverything();
            }
        } catch (LinkageError | RuntimeException exception) {
            lifecycle.error(errorDetail("Baritone lifecycle tick failed", exception));
        }
    }

    private synchronized void handlePathEvent(PathEvent event) {
        if (!lifecycle.owned()) {
            return;
        }
        switch (event) {
            case CALC_STARTED ->
                    lifecycle.pathSignal(
                            BaritoneNavigationLifecycle.PathSignal.CALC_STARTED
                    );
            case CALC_FINISHED_NOW_EXECUTING,
                    NEXT_SEGMENT_CALC_FINISHED,
                    CONTINUING_ONTO_PLANNED_NEXT,
                    SPLICING_ONTO_NEXT_EARLY ->
                    lifecycle.pathSignal(BaritoneNavigationLifecycle.PathSignal.PATHING);
            case AT_GOAL ->
                    lifecycle.pathSignal(BaritoneNavigationLifecycle.PathSignal.AT_GOAL);
            case CALC_FAILED, NEXT_CALC_FAILED ->
                    lifecycle.pathSignal(
                            BaritoneNavigationLifecycle.PathSignal.CALC_FAILED
                    );
            default -> {
                // CANCELED is also emitted while setGoalAndPath replaces an old
                // calculation, so ownership is reconciled from the active goal
                // and liveness timers instead of treating it as an external stop.
                // Other events are segment-level diagnostics.
            }
        }
    }

    private synchronized void handleWorldChange() {
        lifecycle.reset();
        ownedGoal = null;
    }

    private void reconcileOwnership(IBaritone baritone) {
        if (!lifecycle.owned() || lifecycle.paused() || ownedGoal == null) {
            return;
        }
        if (!goalStillOwned(baritone)) {
            lifecycle.observe(
                    Double.POSITIVE_INFINITY,
                    false,
                    false,
                    false,
                    false
            );
        }
    }

    private boolean goalStillOwned(IBaritone baritone) {
        if (baritone.getCustomGoalProcess().isActive()) {
            Goal customGoal = baritone.getCustomGoalProcess().getGoal();
            return customGoal != null && ownedGoal.equals(customGoal);
        }
        Goal currentGoal = baritone.getPathingBehavior().getGoal();
        return currentGoal == null || ownedGoal.equals(currentGoal);
    }

    private IBaritone primaryAndRegister() {
        IBaritone baritone = primary();
        if (registeredBaritone != baritone) {
            baritone.getGameEventHandler().registerEventListener(eventListener);
            registeredBaritone = baritone;
        }
        return baritone;
    }

    private static IBaritone primary() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            throw new IllegalStateException("Baritone primary instance is unavailable");
        }
        return baritone;
    }

    private static double distanceSquared(IBaritone baritone, GoalBlock goal) {
        return distanceSquared(playerFeet(), goal);
    }

    private static BlockPos playerFeet() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? null : minecraft.player.blockPosition();
    }

    private static double distanceSquared(BlockPos feet, GoalBlock goal) {
        if (feet == null) {
            return Double.POSITIVE_INFINITY;
        }
        double x = (double) feet.getX() - goal.x;
        double y = (double) feet.getY() - goal.y;
        double z = (double) feet.getZ() - goal.z;
        return x * x + y * y + z * z;
    }

    private static NavigationResult failure(String prefix, Throwable throwable) {
        return NavigationResult.failure(errorDetail(prefix, throwable));
    }

    private static String errorDetail(String prefix, Throwable throwable) {
        return prefix + " (" + throwable.getClass().getSimpleName() + ")";
    }
}
