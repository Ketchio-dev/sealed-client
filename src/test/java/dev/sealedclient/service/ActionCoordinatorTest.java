package dev.sealedclient.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionCoordinatorTest {
    @Test
    void higherPriorityOwnerPreemptsAndBlocksLowerPriorityOwner() {
        ActionCoordinator coordinator = new ActionCoordinator();

        assertTrue(coordinator.claim(
                ActionCoordinator.Channel.MOVEMENT,
                "walk",
                10,
                2
        ));
        assertTrue(coordinator.owns(ActionCoordinator.Channel.MOVEMENT, "walk"));

        assertTrue(coordinator.claim(
                ActionCoordinator.Channel.MOVEMENT,
                "escape",
                100,
                2
        ));
        assertFalse(coordinator.owns(ActionCoordinator.Channel.MOVEMENT, "walk"));
        assertTrue(coordinator.owns(ActionCoordinator.Channel.MOVEMENT, "escape"));

        assertFalse(coordinator.claim(
                ActionCoordinator.Channel.MOVEMENT,
                "walk",
                50,
                2
        ));
        assertTrue(coordinator.owns(ActionCoordinator.Channel.MOVEMENT, "escape"));
    }

    @Test
    void claimsAreIndependentPerChannelAndEqualPriorityUsesLatestOwner() {
        ActionCoordinator coordinator = new ActionCoordinator();

        assertTrue(coordinator.claim(ActionCoordinator.Channel.ATTACK, "aura", 50, 1));
        assertTrue(coordinator.claim(ActionCoordinator.Channel.ROTATION, "look", 1, 1));
        assertTrue(coordinator.claim(ActionCoordinator.Channel.ATTACK, "crystal", 50, 1));

        assertTrue(coordinator.owns(ActionCoordinator.Channel.ATTACK, "crystal"));
        assertFalse(coordinator.owns(ActionCoordinator.Channel.ATTACK, "aura"));
        assertTrue(coordinator.owns(ActionCoordinator.Channel.ROTATION, "look"));
    }

    @Test
    void invalidClaimsAreRejected() {
        ActionCoordinator coordinator = new ActionCoordinator();

        assertThrows(
                NullPointerException.class,
                () -> coordinator.claim(null, "owner", 1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.claim(ActionCoordinator.Channel.USE, " ", 1, 1)
        );
    }

    @Test
    void diagnosticsExposeOnlyLiveClaimsAsAnImmutableSnapshot() {
        ActionCoordinator coordinator = new ActionCoordinator();
        coordinator.claim(ActionCoordinator.Channel.ATTACK, "crystal", 50, 0);
        coordinator.claim(ActionCoordinator.Channel.MOVEMENT, "walk", 10, 2);

        ActionCoordinator.DiagnosticSnapshot snapshot = coordinator.diagnostics();
        assertEquals("crystal", snapshot.channelOwners()
                .get(ActionCoordinator.Channel.ATTACK));
        assertEquals("walk", snapshot.channelOwners()
                .get(ActionCoordinator.Channel.MOVEMENT));
        assertEquals(0, snapshot.controlledKeyCount());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.channelOwners().put(
                        ActionCoordinator.Channel.USE,
                        "mutate"
                )
        );

        coordinator.beginTick(null);
        assertFalse(coordinator.diagnostics().channelOwners()
                .containsKey(ActionCoordinator.Channel.ATTACK));
    }
}
