package dev.sealedclient.v26.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.COMPLETED;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.ERROR;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.IDLE;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.PLANNING;
import static dev.sealedclient.v26.integration.BaritoneNavigator26.NavigationState.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalBaritoneIntegration26Test {
    private static final String INIT_PROPERTY =
            "dev.sealedclient.test.baritone.initialized";

    @BeforeEach
    void resetProvider() {
        FakeApi.reset();
    }

    @Test
    void absentProviderLoadsFacadeAndFailsClosedWithoutClassLookup() {
        DenyingLoader loader = new DenyingLoader(
                OptionalBaritoneIntegration26Test.class.getClassLoader()
        );

        BaritoneNavigator26 navigator = assertDoesNotThrow(
                () -> OptionalBaritoneIntegration26.discover(
                        false,
                        "",
                        loader
                )
        );

        assertFalse(navigator.available());
        assertEquals(UNAVAILABLE, navigator.status().state());
        assertFalse(navigator.goTo(12, 64, -34).success());
        assertFalse(navigator.pause().success());
        assertFalse(navigator.resume().success());
        assertFalse(navigator.stop().success());
        assertDoesNotThrow(navigator::tick);
        assertDoesNotThrow(navigator::releaseOwnedNavigation);
        assertDoesNotThrow(navigator::resetSession);
        assertEquals(0, loader.deniedLookups);
    }

    @Test
    void installedMetadataWithMissingApiFailsClosed() {
        DenyingLoader loader = new DenyingLoader(
                OptionalBaritoneIntegration26Test.class.getClassLoader()
        );

        BaritoneNavigator26 navigator =
                OptionalBaritoneIntegration26.discover(
                        true,
                        "unsupported",
                        loader
                );

        assertFalse(navigator.available());
        assertEquals("unsupported", navigator.version());
        assertEquals(UNAVAILABLE, navigator.status().state());
        assertTrue(navigator.status().detail().contains("incompatible"));
        assertTrue(loader.deniedLookups > 0);
    }

    @Test
    void capabilityProbeDoesNotInitializeProviderClasses() {
        System.clearProperty(INIT_PROPERTY);
        ReflectiveBaritoneAccess26.ApiNames names = names(
                InitializationProbeApi.class
        );

        BaritoneNavigator26 navigator =
                OptionalBaritoneIntegration26.discover(
                        true,
                        "probe",
                        getClass().getClassLoader(),
                        BaritoneNavigator26.Limits.DEFAULT,
                        names
                );

        assertTrue(navigator.available());
        assertNull(System.getProperty(INIT_PROPERTY));
    }

    @Test
    void goalApiNavigatesAndDetectsArrivalWithoutChatCommands() {
        BaritoneNavigator26 navigator = navigator();

        assertTrue(navigator.goTo(12, 64, -4).success());
        assertEquals(1, FakeApi.provider.primary.custom.setCalls);
        assertEquals(PLANNING, navigator.status().state());

        FakeApi.provider.primary.context.feet =
                new FakePosition(12, 64, -4);
        navigator.tick();

        assertEquals(COMPLETED, navigator.status().state());
        assertFalse(navigator.status().ownedBySealed());
        assertNoCommandAccess();
    }

    @Test
    void statusIsAPureSnapshotAndTickIsTheOnlyPeriodicSample() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        int samplesAfterStart = FakeApi.providerCalls;

        navigator.status();
        navigator.status();
        navigator.movementReserved();

        assertEquals(samplesAfterStart, FakeApi.providerCalls);
        navigator.tick();
        assertEquals(samplesAfterStart + 1, FakeApi.providerCalls);
        assertNoCommandAccess();
    }

    @Test
    void refusesToReplaceAnExistingExternalGoal() {
        FakeGoalBlock external = new FakeGoalBlock(8, 70, 8);
        FakeApi.provider.primary.custom.active = true;
        FakeApi.provider.primary.custom.goal = external;
        FakeApi.provider.primary.pathing.goal = external;
        FakeApi.provider.primary.pathing.pathing = true;
        BaritoneNavigator26 navigator = navigator();

        BaritoneNavigator26.NavigationResult result =
                navigator.goTo(12, 64, -4);

        assertFalse(result.success());
        assertTrue(result.message().contains("another Baritone goal")
                || result.message().contains("Another Baritone goal"));
        assertEquals(0, FakeApi.provider.primary.custom.setCalls);
        assertEquals(0, FakeApi.provider.primary.pathing.cancelCalls);
        assertNoCommandAccess();
    }

    @Test
    void goalLessExternalPathingAlsoBlocksStartAndCancellation() {
        FakeApi.provider.primary.pathing.pathing = true;
        BaritoneNavigator26 navigator = navigator();

        assertFalse(navigator.goTo(12, 64, -4).success());
        assertEquals(0, FakeApi.provider.primary.custom.setCalls);
        assertEquals(0, FakeApi.provider.primary.pathing.cancelCalls);
        assertNoCommandAccess();
    }

    @Test
    void activeCustomProcessWithNullGoalAlsoBlocksStart() {
        FakeApi.provider.primary.custom.active = true;
        FakeApi.provider.primary.custom.goal = null;
        BaritoneNavigator26 navigator = navigator();

        assertFalse(navigator.goTo(12, 64, -4).success());
        assertEquals(0, FakeApi.provider.primary.custom.setCalls);
        assertEquals(0, FakeApi.provider.primary.pathing.cancelCalls);
        assertNoCommandAccess();
    }

    @Test
    void equalButDistinctExternalGoalCannotBeCancelledAsOwned() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeGoalBlock external = new FakeGoalBlock(12, 64, -4);
        FakeApi.provider.primary.custom.goal = external;
        FakeApi.provider.primary.pathing.goal = external;

        BaritoneNavigator26.NavigationResult result = navigator.stop();

        assertTrue(result.success());
        assertFalse(navigator.status().ownedBySealed());
        assertEquals(0, FakeApi.provider.primary.pathing.cancelCalls);
        assertNoCommandAccess();
    }

    @Test
    void externalCancellationIsNotResurrectedByStallRetry() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeApi.provider.primary.custom.active = false;
        FakeApi.provider.primary.custom.goal = null;
        FakeApi.provider.primary.pathing.goal = null;
        FakeApi.provider.primary.pathing.pathing = false;

        for (int tick = 0; tick < 12; tick++) {
            navigator.tick();
        }

        assertEquals(1, FakeApi.provider.primary.custom.setCalls);
        assertEquals(IDLE, navigator.status().state());
        assertFalse(navigator.status().ownedBySealed());
        assertNoCommandAccess();
    }

    @Test
    void pauseResumeStopOperateOnlyOnExactOwnedGoal() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        assertTrue(navigator.pause().success());
        assertEquals(1, FakeApi.provider.primary.pathing.cancelCalls);

        assertTrue(navigator.resume().success());
        assertEquals(2, FakeApi.provider.primary.custom.setCalls);
        assertTrue(navigator.stop().success());
        assertEquals(2, FakeApi.provider.primary.pathing.cancelCalls);
        assertNoCommandAccess();
    }

    @Test
    void uncancelablePauseFailsTruthfullyUntilMovementQuiesces() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeApi.provider.primary.pathing.cancelResult = false;

        assertFalse(navigator.pause().success());
        assertTrue(FakeApi.provider.primary.pathing.pathing);
        assertFalse(navigator.stop().success());

        FakeApi.provider.primary.pathing.pathing = false;
        assertTrue(navigator.stop().success());
        assertNoCommandAccess();
    }

    @Test
    void uncancelableStopNeverReportsImmediateSuccess() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeApi.provider.primary.pathing.cancelResult = false;

        BaritoneNavigator26.NavigationResult first = navigator.stop();

        assertFalse(first.success());
        assertTrue(first.message().contains("uncancelable"));
        assertTrue(FakeApi.provider.primary.pathing.pathing);
        FakeApi.provider.primary.pathing.pathing = false;
        assertTrue(navigator.stop().success());
        assertNoCommandAccess();
    }

    @Test
    void uncancelableStopKeepsMovementReservedUntilTickSeesQuiescence() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeApi.provider.primary.pathing.cancelResult = false;

        assertFalse(navigator.stop().success());
        assertTrue(navigator.movementReserved());
        assertFalse(navigator.status().ownedBySealed());

        FakeApi.provider.primary.pathing.pathing = false;
        navigator.tick();

        assertFalse(navigator.movementReserved());
        assertNoCommandAccess();
    }

    @Test
    void pendingCleanupYieldsToExternalTakeoverWithoutCancellingIt() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeApi.provider.primary.pathing.cancelResult = false;
        assertFalse(navigator.stop().success());
        int cancellations = FakeApi.provider.primary.pathing.cancelCalls;

        FakeGoalBlock external = new FakeGoalBlock(30, 80, 30);
        FakeApi.provider.primary.custom.active = true;
        FakeApi.provider.primary.custom.goal = external;
        FakeApi.provider.primary.pathing.goal = external;
        navigator.tick();

        assertFalse(navigator.movementReserved());
        assertEquals(cancellations, FakeApi.provider.primary.pathing.cancelCalls);
        assertNoCommandAccess();
    }

    @Test
    void externalTakeoverWhilePausedBlocksResumeWithoutCancellation() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        assertTrue(navigator.pause().success());
        int cancellations = FakeApi.provider.primary.pathing.cancelCalls;
        FakeGoalBlock external = new FakeGoalBlock(30, 80, 30);
        FakeApi.provider.primary.custom.active = true;
        FakeApi.provider.primary.custom.goal = external;
        FakeApi.provider.primary.pathing.goal = external;

        assertFalse(navigator.resume().success());
        assertEquals(cancellations, FakeApi.provider.primary.pathing.cancelCalls);
        assertFalse(navigator.status().ownedBySealed());
        assertNoCommandAccess();
    }

    @Test
    void stallCancelsThenRetriesWithBoundedGoalApiCalls() {
        BaritoneNavigator26 navigator =
                OptionalBaritoneIntegration26.discover(
                        true,
                        "fake",
                        getClass().getClassLoader(),
                        new BaritoneNavigator26.Limits(1, 1L, 2L, 20L),
                        names(FakeApi.class)
                );
        assertTrue(navigator.goTo(12, 64, -4).success());

        navigator.tick();
        navigator.tick();
        assertEquals(1, FakeApi.provider.primary.pathing.cancelCalls);
        navigator.tick();

        assertEquals(2, FakeApi.provider.primary.custom.setCalls);
        assertEquals(1, navigator.status().retryCount());
        assertNoCommandAccess();
    }

    @Test
    void retryWaitsUntilUncancelableSegmentActuallyStops() {
        BaritoneNavigator26 navigator =
                OptionalBaritoneIntegration26.discover(
                        true,
                        "fake",
                        getClass().getClassLoader(),
                        new BaritoneNavigator26.Limits(1, 1L, 2L, 20L),
                        names(FakeApi.class)
                );
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeApi.provider.primary.pathing.cancelResult = false;

        navigator.tick();
        navigator.tick();
        for (int tick = 0; tick < 5; tick++) {
            navigator.tick();
        }
        assertEquals(1, FakeApi.provider.primary.custom.setCalls);

        FakeApi.provider.primary.pathing.pathing = false;
        navigator.tick();

        assertEquals(2, FakeApi.provider.primary.custom.setCalls);
        assertEquals(1, navigator.status().retryCount());
        assertNoCommandAccess();
    }

    @Test
    void retryTimeoutNeverBlindCancelsGoalLessActivity() {
        BaritoneNavigator26 navigator =
                OptionalBaritoneIntegration26.discover(
                        true,
                        "fake",
                        getClass().getClassLoader(),
                        new BaritoneNavigator26.Limits(1, 1L, 2L, 4L),
                        names(FakeApi.class)
                );
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeApi.provider.primary.pathing.cancelResult = false;

        navigator.tick();
        navigator.tick();
        assertEquals(1, FakeApi.provider.primary.pathing.cancelCalls);
        navigator.tick();
        navigator.tick();

        assertEquals(1, FakeApi.provider.primary.pathing.cancelCalls);
        assertEquals(
                BaritoneNavigator26.NavigationState.FAILED,
                navigator.status().state()
        );
        assertNoCommandAccess();
    }

    @Test
    void runtimeFailureCancelsLastExactlyObservedOwnedGoal() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeGoalBlock goal =
                (FakeGoalBlock) FakeApi.provider.primary.custom.goal;
        goal.failContainment = true;

        navigator.tick();

        assertFalse(navigator.available());
        assertEquals(ERROR, navigator.status().state());
        assertEquals(1, FakeApi.provider.primary.pathing.cancelCalls);
        assertFalse(FakeApi.provider.primary.pathing.pathing);
        assertNoCommandAccess();
    }

    @Test
    void runtimeFailureKeepsCleanupReservedUntilTickSeesQuiescence() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeGoalBlock goal =
                (FakeGoalBlock) FakeApi.provider.primary.custom.goal;
        goal.failContainment = true;
        FakeApi.provider.primary.pathing.cancelResult = false;

        navigator.tick();

        assertFalse(navigator.available());
        assertEquals(ERROR, navigator.status().state());
        assertTrue(navigator.movementReserved());
        FakeApi.provider.primary.pathing.pathing = false;
        navigator.tick();
        assertFalse(navigator.movementReserved());
        assertNoCommandAccess();
    }

    @Test
    void sessionResetCancelsExactOwnedGoalAndClearsLifecycle() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());

        navigator.resetSession();

        assertEquals(1, FakeApi.provider.primary.pathing.cancelCalls);
        assertEquals(IDLE, navigator.status().state());
        assertFalse(navigator.status().ownedBySealed());
        assertNull(navigator.status().target());
        assertNoCommandAccess();
    }

    @Test
    void sessionResetNeverCancelsExternalTakeover() {
        BaritoneNavigator26 navigator = navigator();
        assertTrue(navigator.goTo(12, 64, -4).success());
        FakeGoalBlock external = new FakeGoalBlock(30, 80, 30);
        FakeApi.provider.primary.custom.goal = external;
        FakeApi.provider.primary.pathing.goal = external;

        navigator.resetSession();

        assertEquals(0, FakeApi.provider.primary.pathing.cancelCalls);
        assertEquals(IDLE, navigator.status().state());
        assertNoCommandAccess();
    }

    @Test
    void providerRuntimeFailureRemainsFailClosedAcrossSessionReset() {
        BaritoneNavigator26 navigator = navigator();
        FakeApi.providerFailure = true;

        assertFalse(navigator.goTo(12, 64, -4).success());
        assertFalse(navigator.available());
        assertEquals(ERROR, navigator.status().state());

        navigator.resetSession();

        assertFalse(navigator.available());
        assertEquals(ERROR, navigator.status().state());
        assertNoCommandAccess();
    }

    private BaritoneNavigator26 navigator() {
        BaritoneNavigator26 navigator =
                OptionalBaritoneIntegration26.discover(
                        true,
                        "fake",
                        getClass().getClassLoader(),
                        new BaritoneNavigator26.Limits(2, 2L, 4L, 40L),
                        names(FakeApi.class)
                );
        assertTrue(navigator.available());
        return navigator;
    }

    private static ReflectiveBaritoneAccess26.ApiNames names(
            Class<?> apiClass
    ) {
        return new ReflectiveBaritoneAccess26.ApiNames(
                apiClass.getName(),
                FakeProvider.class.getName(),
                FakeBaritone.class.getName(),
                FakeCustomProcess.class.getName(),
                FakePathingBehavior.class.getName(),
                FakeGoal.class.getName(),
                FakeGoalBlock.class.getName(),
                FakePlayerContext.class.getName(),
                FakePosition.class.getName()
        );
    }

    private static void assertNoCommandAccess() {
        assertEquals(0, FakeApi.provider.commandSystemCalls);
        assertEquals(0, FakeApi.provider.primary.commandManagerCalls);
    }

    static final class DenyingLoader extends ClassLoader {
        int deniedLookups;

        DenyingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("baritone.")) {
                deniedLookups++;
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    public interface FakeGoal {
    }

    public static final class FakeGoalBlock implements FakeGoal {
        final int x;
        final int y;
        final int z;
        boolean failContainment;

        public FakeGoalBlock(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean isInGoal(int x, int y, int z) {
            if (failContainment) {
                throw new IllegalStateException(
                        "simulated goal containment failure"
                );
            }
            return this.x == x && this.y == y && this.z == z;
        }
    }

    public static final class FakePosition {
        public final int x;
        public final int y;
        public final int z;

        FakePosition(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class FakeCustomProcess {
        boolean active;
        FakeGoal goal;
        int setCalls;

        public boolean isActive() {
            return active;
        }

        public FakeGoal getGoal() {
            return goal;
        }

        public void setGoalAndPath(FakeGoal requestedGoal) {
            goal = requestedGoal;
            active = true;
            setCalls++;
            FakeApi.provider.primary.pathing.goal = requestedGoal;
            FakeApi.provider.primary.pathing.pathing = true;
        }
    }

    public static final class FakePathingBehavior {
        FakeGoal goal;
        boolean pathing;
        int cancelCalls;
        boolean cancelResult = true;

        public FakeGoal getGoal() {
            return goal;
        }

        public boolean isPathing() {
            return pathing;
        }

        public boolean cancelEverything() {
            cancelCalls++;
            FakeApi.provider.primary.custom.active = false;
            FakeApi.provider.primary.custom.goal = null;
            if (cancelResult) {
                pathing = false;
            } else {
                goal = null;
            }
            return cancelResult;
        }

        public Optional<Object> getInProgress() {
            return Optional.empty();
        }
    }

    public static final class FakePlayerContext {
        FakePosition feet = new FakePosition(2, 64, -4);

        public FakePosition playerFeet() {
            return feet;
        }
    }

    public static final class FakeBaritone {
        final FakeCustomProcess custom = new FakeCustomProcess();
        final FakePathingBehavior pathing = new FakePathingBehavior();
        final FakePlayerContext context = new FakePlayerContext();
        int commandManagerCalls;

        public FakeCustomProcess getCustomGoalProcess() {
            return custom;
        }

        public FakePathingBehavior getPathingBehavior() {
            return pathing;
        }

        public FakePlayerContext getPlayerContext() {
            return context;
        }

        public Object getCommandManager() {
            commandManagerCalls++;
            throw new AssertionError("Command manager must not be accessed");
        }
    }

    public static final class FakeProvider {
        final FakeBaritone primary = new FakeBaritone();
        int commandSystemCalls;

        public FakeBaritone getPrimaryBaritone() {
            return primary;
        }

        public Object getCommandSystem() {
            commandSystemCalls++;
            throw new AssertionError("Command system must not be accessed");
        }
    }

    public static final class FakeApi {
        static FakeProvider provider = new FakeProvider();
        static boolean providerFailure;
        static int providerCalls;

        public static FakeProvider getProvider() {
            providerCalls++;
            if (providerFailure) {
                throw new IllegalStateException("simulated provider failure");
            }
            return provider;
        }

        static void reset() {
            provider = new FakeProvider();
            providerFailure = false;
            providerCalls = 0;
        }
    }

    public static final class InitializationProbeApi {
        static {
            System.setProperty(INIT_PROPERTY, "true");
        }

        public static FakeProvider getProvider() {
            return FakeApi.provider;
        }
    }
}
