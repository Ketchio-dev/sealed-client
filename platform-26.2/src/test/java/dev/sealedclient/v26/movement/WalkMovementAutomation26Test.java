package dev.sealedclient.v26.movement;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WalkMovementAutomation26Test {
    @Test
    void defaultConfigurationKeepsEveryLiveLoopAndDeltaBounded() {
        WalkMovementAutomation26.Configuration configuration =
                WalkMovementAutomation26.DEFAULT_CONFIGURATION;

        assertTrue(configuration.maximumHoleScans() <= 512);
        assertTrue(configuration.holeRadius() <= 5);
        assertTrue(configuration.autoCenterSpeed() <= 0.25);
        assertTrue(configuration.holeSnapSpeed() <= 0.35);
        assertTrue(configuration.stepHeight() <= 1.5);
        assertTrue(configuration.maximumStepIncreasePerTick() <= 0.50);
    }

    @Test
    void invalidUnboundedConfigurationIsRejected() {
        WalkMovementAutomation26.Configuration defaults =
                WalkMovementAutomation26.DEFAULT_CONFIGURATION;

        assertThrows(IllegalArgumentException.class, () ->
                copy(defaults, 513, defaults.stepHeight())
        );
        assertThrows(IllegalArgumentException.class, () ->
                copy(defaults, defaults.maximumHoleScans(), 1.6)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new WalkMovementAutomation26.Configuration(
                        Double.NaN,
                        defaults.autoCenterSpeed(),
                        defaults.autoCenterTolerance(),
                        defaults.holeRadius(),
                        defaults.holeSnapSpeed(),
                        defaults.holeSnapTolerance(),
                        defaults.maximumHoleScans(),
                        defaults.stepHeight(),
                        defaults.maximumStepIncreasePerTick()
                )
        );
    }

    @Test
    void safeWalkBundleIsAtomicAgainstHorizontalAndKeyOwners() {
        MovementActionArbiter26 arbiter = new MovementActionArbiter26();
        arbiter.beginTick(MovementActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                WalkMovementAutomation26.SAFE_WALK_OWNER,
                WalkMovementAutomation26.SAFE_WALK_PRIORITY,
                WalkMovementAutomation26.SAFE_WALK_CHANNELS
        );
        arbiter.submit(
                "foreign_key_owner",
                WalkMovementAutomation26.SAFE_WALK_PRIORITY + 1,
                Set.of(MovementActionArbiter26.Channel.KEY_INPUT)
        );
        arbiter.resolve();

        assertFalse(arbiter.ownsAll(
                WalkMovementAutomation26.SAFE_WALK_OWNER,
                WalkMovementAutomation26.SAFE_WALK_CHANNELS
        ));
        assertFalse(arbiter.owns(
                WalkMovementAutomation26.SAFE_WALK_OWNER,
                MovementActionArbiter26.Channel.HORIZONTAL
        ));
    }

    @Test
    void holeSnapDeterministicallyPreemptsCenterButNotStep() {
        MovementActionArbiter26 arbiter = new MovementActionArbiter26();
        arbiter.beginTick(MovementActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                WalkMovementAutomation26.AUTO_CENTER_OWNER,
                WalkMovementAutomation26.AUTO_CENTER_PRIORITY,
                WalkMovementAutomation26.HORIZONTAL_CHANNELS
        );
        arbiter.submit(
                WalkMovementAutomation26.HOLE_SNAP_OWNER,
                WalkMovementAutomation26.HOLE_SNAP_PRIORITY,
                WalkMovementAutomation26.HORIZONTAL_CHANNELS
        );
        arbiter.submit(
                WalkMovementAutomation26.STEP_OWNER,
                WalkMovementAutomation26.STEP_PRIORITY,
                WalkMovementAutomation26.STEP_CHANNELS
        );
        arbiter.resolve();

        assertTrue(arbiter.ownsAll(
                WalkMovementAutomation26.HOLE_SNAP_OWNER,
                WalkMovementAutomation26.HORIZONTAL_CHANNELS
        ));
        assertFalse(arbiter.ownsAll(
                WalkMovementAutomation26.AUTO_CENTER_OWNER,
                WalkMovementAutomation26.HORIZONTAL_CHANNELS
        ));
        assertTrue(arbiter.ownsAll(
                WalkMovementAutomation26.STEP_OWNER,
                WalkMovementAutomation26.STEP_CHANNELS
        ));
    }

    @Test
    void pausedNetworkBlocksEveryMovementChannel() {
        MovementActionArbiter26 arbiter = new MovementActionArbiter26();
        arbiter.beginTick(new MovementActionArbiter26.SafetyContext(
                true,
                true,
                true,
                true,
                false
        ));

        for (MovementActionArbiter26.Channel channel :
                MovementActionArbiter26.Channel.values()) {
            assertFalse(arbiter.submit(
                    "owner_" + channel.name().toLowerCase(),
                    100,
                    Set.of(channel)
            ));
        }
        assertEquals(
                MovementActionArbiter26.SafetyBlock.NETWORK_PAUSED,
                arbiter.snapshot().safetyBlock()
        );
    }

    @Test
    void executionSnapshotIsImmutableAndCarriesSafetyFeedbackMotion() {
        EnumSet<WalkMovementAutomation26.Assist> source =
                EnumSet.of(
                        WalkMovementAutomation26.Assist.HOLE_SNAP,
                        WalkMovementAutomation26.Assist.STEP
                );
        WalkMovementAutomation26.AppliedHorizontal motion =
                new WalkMovementAutomation26.AppliedHorizontal(
                        WalkMovementAutomation26.Assist.HOLE_SNAP,
                        0.12,
                        -0.04,
                        false
                );
        WalkMovementAutomation26.Execution execution =
                new WalkMovementAutomation26.Execution(
                        source,
                        Optional.of(motion),
                        128
                );
        source.clear();

        assertEquals(2, execution.applied().size());
        assertEquals(0.12, execution.horizontal().orElseThrow().x());
        assertThrows(
                UnsupportedOperationException.class,
                () -> execution.applied().add(
                        WalkMovementAutomation26.Assist.AUTO_CENTER
                )
        );
    }

    @Test
    void appliedMotionRejectsNonSteeringAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () ->
                new WalkMovementAutomation26.AppliedHorizontal(
                        WalkMovementAutomation26.Assist.SAFE_WALK,
                        0.0,
                        0.0,
                        true
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new WalkMovementAutomation26.AppliedHorizontal(
                        WalkMovementAutomation26.Assist.AUTO_CENTER,
                        Double.POSITIVE_INFINITY,
                        0.0,
                        false
                )
        );
    }

    private static WalkMovementAutomation26.Configuration copy(
            WalkMovementAutomation26.Configuration defaults,
            int maximumHoleScans,
            double stepHeight
    ) {
        return new WalkMovementAutomation26.Configuration(
                defaults.safeWalkLookAhead(),
                defaults.autoCenterSpeed(),
                defaults.autoCenterTolerance(),
                defaults.holeRadius(),
                defaults.holeSnapSpeed(),
                defaults.holeSnapTolerance(),
                maximumHoleScans,
                stepHeight,
                defaults.maximumStepIncreasePerTick()
        );
    }
}
