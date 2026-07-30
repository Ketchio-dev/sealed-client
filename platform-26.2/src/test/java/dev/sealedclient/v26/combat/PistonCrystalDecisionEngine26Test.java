package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PistonCrystalDecisionEngine26Test {
    private static final PistonCrystalDecisionEngine26.Limits LIMITS =
            new PistonCrystalDecisionEngine26.Limits(16, 8.0, 4.5);

    @Test
    void layoutSelectionRequiresCompleteSafeGeometry() {
        PistonCrystalDecisionEngine26.Layout unsafe =
                layout(1, 2.0, 3.0, false);
        PistonCrystalDecisionEngine26.Layout safe =
                layout(2, 3.0, 3.5, true);

        assertEquals(2L, PistonCrystalDecisionEngine26.selectBest(
                List.of(unsafe, safe),
                LIMITS
        ));
    }

    @Test
    void selectionPrefersInteractionDistanceThenTargetThenKey() {
        assertEquals(3L, PistonCrystalDecisionEngine26.selectBest(
                List.of(
                        layout(9, 2.0, 4.0, true),
                        layout(7, 3.0, 3.0, true),
                        layout(3, 3.0, 3.0, true)
                ),
                LIMITS
        ));
    }

    @Test
    void invalidOrientationOrFriendFailsClosed() {
        PistonCrystalDecisionEngine26.Layout original =
                layout(5, 2.0, 3.0, true);
        PistonCrystalDecisionEngine26.Layout friend = copy(
                original,
                true,
                true
        );
        PistonCrystalDecisionEngine26.Layout wrongOrientation = copy(
                original,
                false,
                false
        );

        assertFalse(PistonCrystalDecisionEngine26.valid(friend, LIMITS));
        assertFalse(PistonCrystalDecisionEngine26.valid(
                wrongOrientation,
                LIMITS
        ));
    }

    @Test
    void geometryMustMatchBasePistonPowerAndFacingRelations() {
        PistonCrystalDecisionEngine26.Layout original =
                layout(12, 2.0, 3.0, true);
        PistonCrystalDecisionEngine26.Layout wrongPower =
                new PistonCrystalDecisionEngine26.Layout(
                        original.key(),
                        original.targetId(),
                        original.base(),
                        original.piston(),
                        new PistonCrystalDecisionEngine26.Cell(
                                2,
                                66,
                                0
                        ),
                        original.facing(),
                        original.targetDistance(),
                        original.interactionDistance(),
                        true,
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                );

        assertFalse(PistonCrystalDecisionEngine26.valid(
                wrongPower,
                LIMITS
        ));
    }

    @Test
    void boundedLayoutScanIgnoresLateCandidate() {
        List<PistonCrystalDecisionEngine26.Layout> layouts =
                new ArrayList<>();
        layouts.add(layout(10, 5.0, 4.0, true));
        layouts.add(layout(11, 4.0, 3.5, true));
        layouts.add(layout(1, 1.0, 1.0, true));

        assertEquals(11L, PistonCrystalDecisionEngine26.selectBest(
                layouts,
                new PistonCrystalDecisionEngine26.Limits(
                        2,
                        8.0,
                        4.5
                )
        ));
    }

    @Test
    void cellsDerivePistonLineWithOppositeFacing() {
        PistonCrystalDecisionEngine26.Cell base =
                new PistonCrystalDecisionEngine26.Cell(4, 64, 8);
        PistonCrystalDecisionEngine26.Cell piston = base
                .offset(
                        PistonCrystalDecisionEngine26.Horizontal.EAST,
                        1
                )
                .above();

        assertEquals(
                new PistonCrystalDecisionEngine26.Cell(5, 65, 8),
                piston
        );
        assertEquals(
                PistonCrystalDecisionEngine26.Horizontal.WEST,
                PistonCrystalDecisionEngine26.Horizontal.EAST.opposite()
        );
    }

    @Test
    void sequenceRequiresEveryReflectedStageBeforeAdvancing() {
        PistonCrystalDecisionEngine26.Sequence sequence =
                new PistonCrystalDecisionEngine26.Sequence(4, 1);
        assertTrue(sequence.begin());
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.ACT,
                sequence.directive(1L, none())
        );
        assertTrue(sequence.markActed(1L));
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.WAIT,
                sequence.directive(2L, none())
        );
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.ACT,
                sequence.directive(
                        3L,
                        observation(true, false, false, false, false)
                )
        );
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Stage.PLACE_CRYSTAL,
                sequence.snapshot().stage()
        );
        assertTrue(sequence.markActed(3L));
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.ACT,
                sequence.directive(
                        4L,
                        observation(true, true, false, false, false)
                )
        );
        assertTrue(sequence.markActed(4L));
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.ACT,
                sequence.directive(
                        5L,
                        observation(true, true, true, true, false)
                )
        );
        assertTrue(sequence.markActed(5L));
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.COMPLETE,
                sequence.directive(
                        6L,
                        observation(true, false, true, true, true)
                )
        );
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Stage.COMPLETE,
                sequence.snapshot().stage()
        );
    }

    @Test
    void sequenceRetryIsStableUntilSentAndThenBounded() {
        PistonCrystalDecisionEngine26.Sequence sequence =
                new PistonCrystalDecisionEngine26.Sequence(2, 1);
        assertTrue(sequence.begin());
        assertTrue(sequence.markActed(10L));
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.RETRY,
                sequence.directive(12L, none())
        );
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.RETRY,
                sequence.directive(13L, none())
        );
        assertEquals(0, sequence.snapshot().retries());
        assertTrue(sequence.markRetried(13L));
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.ABORT,
                sequence.directive(15L, none())
        );
        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Stage.ABORTED,
                sequence.snapshot().stage()
        );
    }

    @Test
    void explicitAbortNeverReportsCompletion() {
        PistonCrystalDecisionEngine26.Sequence sequence =
                new PistonCrystalDecisionEngine26.Sequence(3, 1);
        assertTrue(sequence.begin());
        sequence.abort();

        assertEquals(
                PistonCrystalDecisionEngine26.Sequence.Directive.ABORT,
                sequence.directive(1L, none())
        );
    }

    @Test
    void preexistingOrNearbyCrystalCannotConfirmOwnedPlacement() {
        Set<Integer> before = Set.of(40, 41);
        assertFalse(PistonCrystalDecisionEngine26.acceptsPlacedCrystal(
                before,
                40,
                0.0,
                true
        ));
        assertFalse(PistonCrystalDecisionEngine26.acceptsPlacedCrystal(
                before,
                42,
                0.0,
                false
        ));
        assertFalse(PistonCrystalDecisionEngine26.acceptsPlacedCrystal(
                before,
                42,
                0.37,
                true
        ));
        assertTrue(PistonCrystalDecisionEngine26.acceptsPlacedCrystal(
                before,
                42,
                0.36,
                true
        ));
    }

    @Test
    void cleanupRequiresSentAndReflectedOwnership() {
        assertFalse(PistonCrystalDecisionEngine26.ownsPlacedBlock(
                false,
                true
        ));
        assertFalse(PistonCrystalDecisionEngine26.ownsPlacedBlock(
                true,
                false
        ));
        assertTrue(PistonCrystalDecisionEngine26.ownsPlacedBlock(
                true,
                true
        ));
    }

    @Test
    void observedRemovalPermanentlyRevokesCleanupOwnership() {
        PistonCrystalDecisionEngine26.PlacementOwnership ownership =
                PistonCrystalDecisionEngine26.PlacementOwnership
                        .unconfirmed();
        ownership = PistonCrystalDecisionEngine26.observeOwnership(
                ownership,
                true,
                true
        );
        assertTrue(ownership.owned());

        ownership = PistonCrystalDecisionEngine26.observeOwnership(
                ownership,
                true,
                false
        );
        assertTrue(ownership.revoked());
        assertFalse(ownership.owned());

        ownership = PistonCrystalDecisionEngine26.observeOwnership(
                ownership,
                true,
                true
        );
        assertTrue(ownership.revoked());
        assertFalse(ownership.owned());
    }

    @Test
    void cleanupPreparationReturnsIntentWithoutConsumingState() {
        assertEquals(
                PistonCrystalDecisionEngine26.CleanupDirective.ADVANCE,
                PistonCrystalDecisionEngine26.cleanupDirective(
                        false,
                        false,
                        false,
                        true
                )
        );
        assertEquals(
                PistonCrystalDecisionEngine26.CleanupDirective.ADVANCE,
                PistonCrystalDecisionEngine26.cleanupDirective(
                        true,
                        true,
                        false,
                        true
                )
        );
        assertEquals(
                PistonCrystalDecisionEngine26.CleanupDirective.ABANDON,
                PistonCrystalDecisionEngine26.cleanupDirective(
                        true,
                        false,
                        true,
                        true
                )
        );
        // Re-evaluation is stable when arbitration denies the prepared intent.
        assertEquals(
                PistonCrystalDecisionEngine26.CleanupDirective.ABANDON,
                PistonCrystalDecisionEngine26.cleanupDirective(
                        true,
                        false,
                        true,
                        true
                )
        );
    }

    @Test
    void explosionPointUsesActualCrystalYAndHorizontalPush() {
        PistonCrystalDecisionEngine26.ExplosionPoint point =
                PistonCrystalDecisionEngine26.explosionPoint(
                        new PistonCrystalDecisionEngine26.Cell(4, 64, 8),
                        PistonCrystalDecisionEngine26.Horizontal.WEST
                );

        assertEquals(3.5, point.x());
        assertEquals(65.0, point.y());
        assertEquals(8.5, point.z());
    }

    @Test
    void eachLiveActionReachUsesTheConfiguredExactBound() {
        assertTrue(PistonCrystalDecisionEngine26.withinRange(
                20.25,
                4.5
        ));
        assertFalse(PistonCrystalDecisionEngine26.withinRange(
                Math.nextUp(20.25),
                4.5
        ));
        assertFalse(PistonCrystalDecisionEngine26.withinRange(
                Double.NaN,
                4.5
        ));
    }

    private static PistonCrystalDecisionEngine26.Layout layout(
            long key,
            double targetDistance,
            double interactionDistance,
            boolean safe
    ) {
        return new PistonCrystalDecisionEngine26.Layout(
                key,
                9,
                new PistonCrystalDecisionEngine26.Cell(0, 64, 0),
                new PistonCrystalDecisionEngine26.Cell(1, 65, 0),
                new PistonCrystalDecisionEngine26.Cell(1, 66, 0),
                PistonCrystalDecisionEngine26.Horizontal.WEST,
                targetDistance,
                interactionDistance,
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                safe
        );
    }

    private static PistonCrystalDecisionEngine26.Layout copy(
            PistonCrystalDecisionEngine26.Layout original,
            boolean friend,
            boolean orientation
    ) {
        return new PistonCrystalDecisionEngine26.Layout(
                original.key(),
                original.targetId(),
                original.base(),
                original.piston(),
                original.power(),
                original.facing(),
                original.targetDistance(),
                original.interactionDistance(),
                original.targetValid(),
                friend,
                original.lineOfSight(),
                original.baseValid(),
                original.crystalSpaceClear(),
                original.pistonSpaceClear(),
                original.pistonSupport(),
                original.powerSpaceClear(),
                orientation,
                original.explosionSafe()
        );
    }

    private static PistonCrystalDecisionEngine26.Sequence.Observation none() {
        return PistonCrystalDecisionEngine26.Sequence.Observation.none();
    }

    private static PistonCrystalDecisionEngine26.Sequence.Observation observation(
            boolean piston,
            boolean crystal,
            boolean power,
            boolean extended,
            boolean gone
    ) {
        return new PistonCrystalDecisionEngine26.Sequence.Observation(
                piston,
                crystal,
                power,
                extended,
                gone
        );
    }
}
