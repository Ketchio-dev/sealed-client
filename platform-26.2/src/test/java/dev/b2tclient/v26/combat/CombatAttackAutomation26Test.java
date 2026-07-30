package dev.b2tclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatAttackAutomation26Test {
    @Test
    void targetSelectionChoosesNearestValidCandidate() {
        int selected = CombatAttackAutomation26.selectTargetId(List.of(
                new CombatAttackAutomation26.TargetCandidate(9, 4.0, true, true),
                new CombatAttackAutomation26.TargetCandidate(3, 1.0, true, false),
                new CombatAttackAutomation26.TargetCandidate(7, 2.0, true, true)
        ), 9.0);

        assertEquals(7, selected);
    }

    @Test
    void targetSelectionIsStableOnDistanceTies() {
        int selected = CombatAttackAutomation26.selectTargetId(List.of(
                new CombatAttackAutomation26.TargetCandidate(12, 4.0, false, true),
                new CombatAttackAutomation26.TargetCandidate(8, 4.0, true, true),
                new CombatAttackAutomation26.TargetCandidate(4, 4.0, true, true)
        ), 9.0);

        assertEquals(4, selected);
    }

    @Test
    void targetSelectionRejectsUnsafeDistanceInputs() {
        List<CombatAttackAutomation26.TargetCandidate> candidates = List.of(
                new CombatAttackAutomation26.TargetCandidate(1, -1.0, true, true),
                new CombatAttackAutomation26.TargetCandidate(2, Double.NaN, true, true),
                new CombatAttackAutomation26.TargetCandidate(3, 10.0, true, true)
        );

        assertEquals(-1, CombatAttackAutomation26.selectTargetId(candidates, 9.0));
        assertEquals(-1, CombatAttackAutomation26.selectTargetId(candidates, Double.NaN));
        assertEquals(-1, CombatAttackAutomation26.selectTargetId(null, 9.0));
    }

    @Test
    void cooldownRequiresVanillaStrengthAndMinimumTickGap() {
        assertTrue(CombatAttackAutomation26.cooldownReady(
                0.92F, 100, Integer.MIN_VALUE, 1, 0.92F
        ));
        assertFalse(CombatAttackAutomation26.cooldownReady(
                0.91F, 100, Integer.MIN_VALUE, 1, 0.92F
        ));
        assertFalse(CombatAttackAutomation26.cooldownReady(
                1.0F, 100, 100, 1, 0.92F
        ));
        assertTrue(CombatAttackAutomation26.cooldownReady(
                1.0F, 101, 100, 1, 0.92F
        ));
    }

    @Test
    void cooldownRecoversWhenAWorldTickCounterRestarts() {
        assertTrue(CombatAttackAutomation26.cooldownReady(
                1.0F, 2, 8_000, 1, 0.92F
        ));
        assertFalse(CombatAttackAutomation26.cooldownReady(
                Float.NaN, 2, Integer.MIN_VALUE, 1, 0.92F
        ));
    }

    @Test
    void packetCriticalOnlyAllowsStableGroundedContext() {
        CombatAttackAutomation26.CriticalContext safe = context(
                true, false, false, false, false, false, false, false, 0.0F, 0.0
        );

        assertTrue(CombatAttackAutomation26.criticalPacketAllowed(safe));
        assertFalse(CombatAttackAutomation26.criticalPacketAllowed(context(
                false, false, false, false, false, false, false, false, 0.0F, -0.1
        )));
        assertFalse(CombatAttackAutomation26.criticalPacketAllowed(context(
                true, false, false, true, false, false, false, false, 0.0F, 0.0
        )));
        assertFalse(CombatAttackAutomation26.criticalPacketAllowed(context(
                true, true, false, false, false, false, false, false, 0.0F, 0.0
        )));
        assertFalse(CombatAttackAutomation26.criticalPacketAllowed(context(
                true, false, false, false, false, false, false, true, 0.0F, 0.0
        )));
        assertFalse(CombatAttackAutomation26.criticalPacketAllowed(context(
                true, false, false, false, false, false, false, false, 0.1F, 0.0
        )));
    }

    @Test
    void emptyPreparedAttackRequestsNoMutationChannel() {
        CombatAttackAutomation26.PreparedAttack prepared =
                CombatAttackAutomation26.PreparedAttack.none();

        assertFalse(prepared.requested());
        assertFalse(prepared.requiresAttackChannel());
        assertEquals(-1, prepared.targetEntityId());
        assertEquals(List.of(), prepared.requestedChannels().stream().toList());
    }

    @Test
    void simultaneousModulesUseKillAuraSettingsAfterTriggerMiss() {
        CombatAttackAutomation26.AttackSettings triggerSettings =
                new CombatAttackAutomation26.AttackSettings(2.0, 0.99F, 12);
        CombatAttackAutomation26.AttackSettings auraSettings =
                new CombatAttackAutomation26.AttackSettings(3.0, 0.61F, 3);

        CombatAttackAutomation26.AttackSelection selection =
                CombatAttackAutomation26.selectAttackSettings(
                        true,
                        false,
                        triggerSettings,
                        true,
                        true,
                        auraSettings
                );

        assertTrue(selection.selected());
        assertEquals(
                CombatAttackAutomation26.AttackSource.KILL_AURA,
                selection.source()
        );
        assertEquals(auraSettings, selection.settings());
    }

    @Test
    void simultaneousModulesRetainTriggerPriorityAndItsOwnSettingsOnHit() {
        CombatAttackAutomation26.AttackSettings triggerSettings =
                new CombatAttackAutomation26.AttackSettings(2.4, 0.97F, 8);
        CombatAttackAutomation26.AttackSettings auraSettings =
                new CombatAttackAutomation26.AttackSettings(3.0, 0.55F, 2);

        CombatAttackAutomation26.AttackSelection selection =
                CombatAttackAutomation26.selectAttackSettings(
                        true,
                        true,
                        triggerSettings,
                        true,
                        true,
                        auraSettings
                );

        assertEquals(
                CombatAttackAutomation26.AttackSource.TRIGGER_BOT,
                selection.source()
        );
        assertEquals(triggerSettings, selection.settings());
    }

    private static CombatAttackAutomation26.CriticalContext context(
            boolean onGround,
            boolean flying,
            boolean passenger,
            boolean inWater,
            boolean inLava,
            boolean climbing,
            boolean fallFlying,
            boolean horizontalCollision,
            double fallDistance,
            double verticalMotion
    ) {
        return new CombatAttackAutomation26.CriticalContext(
                onGround,
                flying,
                passenger,
                inWater,
                inLava,
                climbing,
                fallFlying,
                horizontalCollision,
                fallDistance,
                verticalMotion
        );
    }
}
