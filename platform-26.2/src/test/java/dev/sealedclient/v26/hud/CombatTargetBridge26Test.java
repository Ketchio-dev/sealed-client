package dev.sealedclient.v26.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatTargetBridge26Test {
    private final CombatTargetBridge26 bridge = new CombatTargetBridge26();

    @Test
    void aSelectionIsVisibleOnlyOnTheTickItWasMade() {
        bridge.observe(42, CombatTargetBridge26.Source.KILL_AURA, 100);

        assertEquals(42, bridge.entityId(100));
        assertEquals(CombatTargetBridge26.Source.KILL_AURA, bridge.source(100));

        assertEquals(CombatTargetBridge26.NO_TARGET, bridge.entityId(101),
                "a stale selection must not linger on the HUD");
        assertEquals(CombatTargetBridge26.Source.NONE, bridge.source(101));
    }

    @Test
    void anAbsentSelectionClearsThePreviousOne() {
        bridge.observe(42, CombatTargetBridge26.Source.AUTO_CRYSTAL, 100);
        bridge.observe(-1, CombatTargetBridge26.Source.AUTO_CRYSTAL, 101);

        assertEquals(CombatTargetBridge26.NO_TARGET, bridge.entityId(101));
        assertEquals(CombatTargetBridge26.Source.NONE, bridge.source(101));
    }

    @Test
    void aNoneSourceIsTreatedAsNoSelection() {
        bridge.observe(7, CombatTargetBridge26.Source.NONE, 5);
        assertEquals(CombatTargetBridge26.NO_TARGET, bridge.entityId(5));

        bridge.observe(7, null, 5);
        assertEquals(CombatTargetBridge26.NO_TARGET, bridge.entityId(5));
    }

    @Test
    void clearDropsTheSelectionForLifecycleTeardown() {
        bridge.observe(42, CombatTargetBridge26.Source.TRIGGER_BOT, 100);
        bridge.clear();

        assertEquals(CombatTargetBridge26.NO_TARGET, bridge.entityId(100));
        assertEquals(CombatTargetBridge26.Source.NONE, bridge.source(100));
    }

    @Test
    void everySourceCarriesADistinctHudLabel() {
        assertEquals("Aura", CombatTargetBridge26.Source.KILL_AURA.label());
        assertEquals("Trigger", CombatTargetBridge26.Source.TRIGGER_BOT.label());
        assertEquals("Crystal", CombatTargetBridge26.Source.AUTO_CRYSTAL.label());
        assertEquals("", CombatTargetBridge26.Source.NONE.label());
    }
}
