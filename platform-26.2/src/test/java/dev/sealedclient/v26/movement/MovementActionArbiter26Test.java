package dev.sealedclient.v26.movement;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static dev.sealedclient.v26.movement.MovementActionArbiter26.Channel.HORIZONTAL;
import static dev.sealedclient.v26.movement.MovementActionArbiter26.Channel.HOTBAR;
import static dev.sealedclient.v26.movement.MovementActionArbiter26.Channel.INVENTORY;
import static dev.sealedclient.v26.movement.MovementActionArbiter26.Channel.KEY_INPUT;
import static dev.sealedclient.v26.movement.MovementActionArbiter26.Channel.PACKET;
import static dev.sealedclient.v26.movement.MovementActionArbiter26.Channel.ROTATION;
import static dev.sealedclient.v26.movement.MovementActionArbiter26.Channel.VERTICAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementActionArbiter26Test {
    @Test
    void resolvesCompleteBundlesByPriorityAndKeepsIndependentChannels() {
        MovementActionArbiter26 arbiter = readyArbiter();

        assertTrue(arbiter.submit(
                "elytra_control",
                70,
                EnumSet.of(HORIZONTAL, VERTICAL, ROTATION)
        ));
        assertTrue(arbiter.submit(
                "hole_snap",
                90,
                EnumSet.of(HORIZONTAL, KEY_INPUT)
        ));
        assertTrue(arbiter.submit(
                "elytra_swap",
                80,
                EnumSet.of(INVENTORY, HOTBAR)
        ));
        arbiter.resolve();

        assertTrue(arbiter.ownsAll(
                "hole_snap",
                EnumSet.of(HORIZONTAL, KEY_INPUT)
        ));
        assertFalse(arbiter.owns("elytra_control", VERTICAL));
        assertEquals(
                MovementActionArbiter26.DecisionStatus.DENIED,
                arbiter.decision("elytra_control").status()
        );
        assertEquals(
                Map.of(HORIZONTAL, "hole_snap"),
                arbiter.decision("elytra_control").blockers()
        );
        assertTrue(arbiter.ownsAll(
                "elytra_swap",
                EnumSet.of(INVENTORY, HOTBAR)
        ));
    }

    @Test
    void allSideEffectChannelsCanBeClaimedAsOneAtomicAction() {
        MovementActionArbiter26 arbiter = readyArbiter();
        Set<MovementActionArbiter26.Channel> everyChannel =
                EnumSet.allOf(MovementActionArbiter26.Channel.class);

        arbiter.submit("composite", 100, everyChannel);
        arbiter.submit("packet_only", 1, Set.of(PACKET));
        arbiter.resolve();

        assertTrue(arbiter.ownsAll("composite", everyChannel));
        assertFalse(arbiter.owns("packet_only", PACKET));
        assertEquals(
                everyChannel,
                arbiter.decision("composite").requestedChannels()
        );
    }

    @Test
    void equalPriorityTieIsOwnerOrderedRegardlessOfSubmissionOrder() {
        MovementActionArbiter26 forward = readyArbiter();
        forward.submit("zeta", 50, Set.of(HORIZONTAL));
        forward.submit("alpha", 50, Set.of(HORIZONTAL));
        forward.resolve();

        MovementActionArbiter26 reverse = readyArbiter();
        reverse.submit("alpha", 50, Set.of(HORIZONTAL));
        reverse.submit("zeta", 50, Set.of(HORIZONTAL));
        reverse.resolve();

        assertEquals(
                forward.snapshot().channelGrants(),
                reverse.snapshot().channelGrants()
        );
        assertEquals(
                "alpha",
                forward.snapshot().channelGrants().get(HORIZONTAL).owner()
        );
    }

    @Test
    void grantsExpireAtNextTickAndUnsafeTicksGrantNothing() {
        MovementActionArbiter26 arbiter = readyArbiter();
        arbiter.submit("speed", 50, Set.of(HORIZONTAL));
        arbiter.resolve();
        assertTrue(arbiter.owns("speed", HORIZONTAL));

        arbiter.beginTick(new MovementActionArbiter26.SafetyContext(
                true,
                true,
                true,
                true,
                false
        ));

        assertEquals(2L, arbiter.tick());
        assertFalse(arbiter.owns("speed", HORIZONTAL));
        assertFalse(arbiter.submit("speed", 50, Set.of(HORIZONTAL)));
        assertEquals(
                MovementActionArbiter26.SafetyBlock.NETWORK_PAUSED,
                arbiter.snapshot().safetyBlock()
        );
        assertEquals(
                MovementActionArbiter26.DecisionStatus.SAFETY_BLOCKED,
                arbiter.decision("speed").status()
        );
    }

    @Test
    void safetyReasonOrderIsDeterministic() {
        assertEquals(
                MovementActionArbiter26.SafetyBlock.NO_SESSION,
                new MovementActionArbiter26.SafetyContext(
                        false,
                        false,
                        false,
                        false,
                        false
                ).block()
        );
        assertEquals(
                MovementActionArbiter26.SafetyBlock.NO_PLAYER,
                new MovementActionArbiter26.SafetyContext(
                        true,
                        false,
                        false,
                        false,
                        false
                ).block()
        );
        assertEquals(
                MovementActionArbiter26.SafetyBlock.PLAYER_DEAD,
                new MovementActionArbiter26.SafetyContext(
                        true,
                        true,
                        false,
                        false,
                        false
                ).block()
        );
        assertEquals(
                MovementActionArbiter26.SafetyBlock.SCREEN_OPEN,
                new MovementActionArbiter26.SafetyContext(
                        true,
                        true,
                        true,
                        false,
                        false
                ).block()
        );
        assertEquals(
                MovementActionArbiter26.SafetyBlock.NETWORK_PAUSED,
                new MovementActionArbiter26.SafetyContext(
                        true,
                        true,
                        true,
                        true,
                        false
                ).block()
        );
    }

    @Test
    void releaseClearsWholeBundleWithoutPromotingDeniedRequests() {
        MovementActionArbiter26 arbiter = readyArbiter();
        arbiter.submit(
                "snap",
                100,
                EnumSet.of(HORIZONTAL, KEY_INPUT)
        );
        arbiter.submit("walk", 50, Set.of(KEY_INPUT));
        arbiter.resolve();

        arbiter.releaseOwner("snap");

        assertFalse(arbiter.owns("snap", HORIZONTAL));
        assertFalse(arbiter.owns("walk", KEY_INPUT));
        assertTrue(arbiter.snapshot().channelGrants().isEmpty());
        assertEquals(
                MovementActionArbiter26.DecisionStatus.RELEASED,
                arbiter.decision("snap").status()
        );
        assertEquals(
                MovementActionArbiter26.DecisionStatus.DENIED,
                arbiter.decision("walk").status()
        );
    }

    @Test
    void releaseAllCancelsCollectingAndResolvedActions() {
        MovementActionArbiter26 collecting = readyArbiter();
        collecting.submit("walk", 10, Set.of(KEY_INPUT));
        collecting.releaseAll();

        assertEquals(
                MovementActionArbiter26.Phase.RESOLVED,
                collecting.snapshot().phase()
        );
        assertThrows(
                IllegalStateException.class,
                () -> collecting.submit("later", 1, Set.of(HORIZONTAL))
        );

        MovementActionArbiter26 resolved = readyArbiter();
        resolved.submit("nofall", 100, Set.of(PACKET));
        resolved.resolve();
        resolved.releaseAll();
        assertFalse(resolved.owns("nofall", PACKET));
        assertTrue(resolved.snapshot().channelGrants().isEmpty());
    }

    @Test
    void snapshotsAndNestedDiagnosticsAreImmutable() {
        MovementActionArbiter26 arbiter = readyArbiter();
        arbiter.submit(
                "flight",
                75,
                EnumSet.of(HORIZONTAL, VERTICAL, ROTATION)
        );
        arbiter.resolve();

        MovementActionArbiter26.Snapshot snapshot = arbiter.snapshot();
        assertEquals(1, snapshot.submittedCount());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.channelGrants().put(
                        PACKET,
                        new MovementActionArbiter26.Grant("mutant", 1)
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.decisions().put(
                        "mutant",
                        arbiter.decision("missing")
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.decisions()
                        .get("flight")
                        .requestedChannels()
                        .add(PACKET)
        );
    }

    @Test
    void invalidAndDuplicateRequestsFailSafely() {
        MovementActionArbiter26 idle = new MovementActionArbiter26();
        assertThrows(
                IllegalStateException.class,
                () -> idle.submit("walk", 1, Set.of(KEY_INPUT))
        );

        MovementActionArbiter26 arbiter = readyArbiter();
        assertThrows(
                IllegalArgumentException.class,
                () -> arbiter.submit(" ", 1, Set.of(KEY_INPUT))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> arbiter.submit("empty", 1, Set.of())
        );
        assertTrue(arbiter.submit("walk", 10, Set.of(KEY_INPUT)));
        assertFalse(arbiter.submit("walk", 1_000, Set.of(PACKET)));
        arbiter.resolve();

        assertTrue(arbiter.owns("walk", KEY_INPUT));
        assertFalse(arbiter.owns("walk", PACKET));
    }

    private static MovementActionArbiter26 readyArbiter() {
        MovementActionArbiter26 arbiter = new MovementActionArbiter26();
        arbiter.beginTick(MovementActionArbiter26.SafetyContext.ready());
        return arbiter;
    }
}
