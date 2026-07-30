package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static dev.sealedclient.v26.combat.CombatActionArbiter26.Channel.ATTACK;
import static dev.sealedclient.v26.combat.CombatActionArbiter26.Channel.HOTBAR;
import static dev.sealedclient.v26.combat.CombatActionArbiter26.Channel.INVENTORY;
import static dev.sealedclient.v26.combat.CombatActionArbiter26.Channel.MOVEMENT;
import static dev.sealedclient.v26.combat.CombatActionArbiter26.Channel.ROTATION;
import static dev.sealedclient.v26.combat.CombatActionArbiter26.Channel.USE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatActionArbiter26Test {
    @Test
    void resolvesAtomicBundlesByPriorityAndKeepsIndependentChannels() {
        CombatActionArbiter26 arbiter = readyArbiter();

        assertTrue(arbiter.submit(
                "kill_aura.attack",
                50,
                EnumSet.of(ATTACK, ROTATION)
        ));
        assertTrue(arbiter.submit(
                "auto_crystal.break",
                100,
                EnumSet.of(ATTACK, HOTBAR, ROTATION)
        ));
        assertTrue(arbiter.submit(
                "auto_totem.swap",
                80,
                EnumSet.of(INVENTORY)
        ));
        arbiter.resolve();

        assertTrue(arbiter.ownsAll(
                "auto_crystal.break",
                EnumSet.of(ATTACK, HOTBAR, ROTATION)
        ));
        assertFalse(arbiter.owns("kill_aura.attack", ROTATION));
        assertEquals(
                CombatActionArbiter26.DecisionStatus.DENIED,
                arbiter.decision("kill_aura.attack").status()
        );
        assertEquals(
                Map.of(
                        ATTACK, "auto_crystal.break",
                        ROTATION, "auto_crystal.break"
                ),
                arbiter.decision("kill_aura.attack").blockers()
        );
        assertTrue(arbiter.owns("auto_totem.swap", INVENTORY));
    }

    @Test
    void movementCanBeClaimedAtomicallyWithPlacementChannels() {
        CombatActionArbiter26 arbiter = readyArbiter();

        arbiter.submit(
                "burrow.jump",
                82,
                EnumSet.of(MOVEMENT, HOTBAR, USE)
        );
        arbiter.submit("surround.place", 70, EnumSet.of(HOTBAR, USE));
        arbiter.resolve();

        assertTrue(arbiter.ownsAll(
                "burrow.jump",
                EnumSet.of(MOVEMENT, HOTBAR, USE)
        ));
        assertFalse(arbiter.owns("surround.place", USE));
    }

    @Test
    void emergencyOffhandSwapExcludesHotbarUseInTheSameTick() {
        CombatActionArbiter26 arbiter = readyArbiter();

        arbiter.submit(
                "auto_totem",
                100,
                EnumSet.of(INVENTORY, HOTBAR, USE)
        );
        arbiter.submit(
                "auto_crystal.place",
                90,
                EnumSet.of(HOTBAR, USE)
        );
        arbiter.resolve();

        assertTrue(arbiter.ownsAll(
                "auto_totem",
                EnumSet.of(INVENTORY, HOTBAR, USE)
        ));
        assertFalse(arbiter.owns("auto_crystal.place", HOTBAR));
        assertFalse(arbiter.owns("auto_crystal.place", USE));
    }

    @Test
    void equalPriorityTieIsOwnerOrderedRegardlessOfSubmissionOrder() {
        CombatActionArbiter26 forward = readyArbiter();
        forward.submit("zeta", 50, Set.of(USE));
        forward.submit("alpha", 50, Set.of(USE));
        forward.resolve();

        CombatActionArbiter26 reverse = readyArbiter();
        reverse.submit("alpha", 50, Set.of(USE));
        reverse.submit("zeta", 50, Set.of(USE));
        reverse.resolve();

        assertEquals(
                forward.snapshot().channelGrants(),
                reverse.snapshot().channelGrants()
        );
        assertEquals("alpha", forward.snapshot()
                .channelGrants()
                .get(USE)
                .owner());
    }

    @Test
    void everyGrantExpiresAtTheNextTickAndUnsafeTicksGrantNothing() {
        CombatActionArbiter26 arbiter = readyArbiter();
        arbiter.submit("aura", 40, Set.of(ATTACK));
        arbiter.resolve();
        assertTrue(arbiter.owns("aura", ATTACK));

        arbiter.beginTick(new CombatActionArbiter26.SafetyContext(
                true,
                true,
                false,
                true
        ));

        assertEquals(2L, arbiter.tick());
        assertFalse(arbiter.owns("aura", ATTACK));
        assertFalse(arbiter.submit("aura", 40, Set.of(ATTACK)));
        assertEquals(
                CombatActionArbiter26.SafetyBlock.PLAYER_DEAD,
                arbiter.snapshot().safetyBlock()
        );
        assertEquals(
                CombatActionArbiter26.DecisionStatus.SAFETY_BLOCKED,
                arbiter.decision("aura").status()
        );
        assertEquals(
                CombatActionArbiter26.SafetyBlock.PLAYER_DEAD,
                arbiter.decision("aura").safetyBlock().orElseThrow()
        );
    }

    @Test
    void releaseOwnerClearsItsWholeBundleWithoutPromotingDeniedActions() {
        CombatActionArbiter26 arbiter = readyArbiter();
        arbiter.submit("crystal", 100, EnumSet.of(ATTACK, ROTATION));
        arbiter.submit("aura", 50, EnumSet.of(ATTACK, ROTATION));
        arbiter.resolve();

        arbiter.releaseOwner("crystal");

        assertFalse(arbiter.owns("crystal", ATTACK));
        assertFalse(arbiter.owns("aura", ATTACK));
        assertTrue(arbiter.snapshot().channelGrants().isEmpty());
        assertEquals(
                CombatActionArbiter26.DecisionStatus.RELEASED,
                arbiter.decision("crystal").status()
        );
        assertEquals(
                CombatActionArbiter26.DecisionStatus.DENIED,
                arbiter.decision("aura").status()
        );
    }

    @Test
    void releaseAllCancelsCollectionAndResolvedGrants() {
        CombatActionArbiter26 collecting = readyArbiter();
        collecting.submit("eat", 10, EnumSet.of(HOTBAR, USE));
        collecting.releaseAll();

        assertEquals(
                CombatActionArbiter26.Phase.RESOLVED,
                collecting.snapshot().phase()
        );
        assertEquals(
                CombatActionArbiter26.DecisionStatus.RELEASED,
                collecting.decision("eat").status()
        );
        assertThrows(
                IllegalStateException.class,
                () -> collecting.submit("later", 1, Set.of(USE))
        );

        CombatActionArbiter26 resolved = readyArbiter();
        resolved.submit("totem", 100, Set.of(INVENTORY));
        resolved.resolve();
        resolved.releaseAll();
        assertTrue(resolved.snapshot().channelGrants().isEmpty());
        assertFalse(resolved.owns("totem", INVENTORY));
    }

    @Test
    void snapshotsAreImmutableStableDiagnostics() {
        CombatActionArbiter26 arbiter = readyArbiter();
        arbiter.submit("crystal", 75, EnumSet.of(ATTACK, ROTATION));
        arbiter.resolve();

        CombatActionArbiter26.Snapshot snapshot = arbiter.snapshot();
        assertEquals(1, snapshot.submittedCount());
        assertEquals(
                new CombatActionArbiter26.Grant("crystal", 75),
                snapshot.channelGrants().get(ATTACK)
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.channelGrants().put(
                        USE,
                        new CombatActionArbiter26.Grant("mutant", 1)
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
                        .get("crystal")
                        .requestedChannels()
                        .add(USE)
        );
    }

    @Test
    void invalidAndDuplicateRequestsFailSafely() {
        CombatActionArbiter26 idle = new CombatActionArbiter26();
        assertThrows(
                IllegalStateException.class,
                () -> idle.submit("aura", 1, Set.of(ATTACK))
        );

        CombatActionArbiter26 arbiter = readyArbiter();
        assertThrows(
                IllegalArgumentException.class,
                () -> arbiter.submit(" ", 1, Set.of(ATTACK))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> arbiter.submit("empty", 1, Set.of())
        );
        assertTrue(arbiter.submit("aura", 10, Set.of(ATTACK)));
        assertFalse(arbiter.submit("aura", 1_000, Set.of(USE)));
        arbiter.resolve();

        assertTrue(arbiter.owns("aura", ATTACK));
        assertFalse(arbiter.owns("aura", USE));
    }

    @Test
    void safetyReasonOrderIsDeterministic() {
        assertEquals(
                CombatActionArbiter26.SafetyBlock.NO_SESSION,
                new CombatActionArbiter26.SafetyContext(
                        false,
                        false,
                        false,
                        false
                ).block()
        );
        assertEquals(
                CombatActionArbiter26.SafetyBlock.NO_PLAYER,
                new CombatActionArbiter26.SafetyContext(
                        true,
                        false,
                        false,
                        false
                ).block()
        );
        assertEquals(
                CombatActionArbiter26.SafetyBlock.SCREEN_OPEN,
                new CombatActionArbiter26.SafetyContext(
                        true,
                        true,
                        true,
                        false
                ).block()
        );
    }

    private static CombatActionArbiter26 readyArbiter() {
        CombatActionArbiter26 arbiter = new CombatActionArbiter26();
        arbiter.beginTick(CombatActionArbiter26.SafetyContext.ready());
        return arbiter;
    }
}
