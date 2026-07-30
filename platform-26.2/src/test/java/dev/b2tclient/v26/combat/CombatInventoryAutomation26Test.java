package dev.b2tclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatInventoryAutomation26Test {
    @Test
    void autoTotemOverridesConfiguredOffhandAtCriticalHealth() {
        CombatInventoryAutomation26.DesiredOffhand desired =
                CombatInventoryAutomation26.desiredOffhand(
                        true,
                        true,
                        12.0F,
                        CombatInventoryAutomation26.DEFAULT_CONFIGURATION
                );

        assertEquals("auto_totem", desired.owner());
        assertEquals(
                CombatInventoryAutomation26.OffhandItem.TOTEM,
                desired.item()
        );
    }

    @Test
    void offhandUsesEmergencyTotemAndReturnsToPreferredItemWhenSafe() {
        CombatInventoryAutomation26.DesiredOffhand emergency =
                CombatInventoryAutomation26.desiredOffhand(
                        false,
                        true,
                        16.0F,
                        CombatInventoryAutomation26.DEFAULT_CONFIGURATION
                );
        CombatInventoryAutomation26.DesiredOffhand safe =
                CombatInventoryAutomation26.desiredOffhand(
                        false,
                        true,
                        17.0F,
                        CombatInventoryAutomation26.DEFAULT_CONFIGURATION
                );

        assertEquals(
                CombatInventoryAutomation26.OffhandItem.TOTEM,
                emergency.item()
        );
        assertEquals(
                CombatInventoryAutomation26.OffhandItem.END_CRYSTAL,
                safe.item()
        );
        assertNull(CombatInventoryAutomation26.desiredOffhand(
                false,
                false,
                10.0F,
                CombatInventoryAutomation26.DEFAULT_CONFIGURATION
        ));
        assertNull(CombatInventoryAutomation26.desiredOffhand(
                true,
                true,
                Float.NaN,
                CombatInventoryAutomation26.DEFAULT_CONFIGURATION
        ));
    }

    @Test
    void simultaneousModulesUseThePolicyOwnedByTheSelectedAction() {
        var configuration = new CombatInventoryAutomation26.Configuration(
                10.0F,
                16.0F,
                CombatInventoryAutomation26.OffhandItem.SHIELD,
                true,
                false,
                11,
                true,
                2,
                3
        );

        CombatInventoryAutomation26.DesiredOffhand autoTotem =
                CombatInventoryAutomation26.desiredOffhand(
                        true,
                        true,
                        9.0F,
                        configuration
                );
        CombatInventoryAutomation26.DesiredOffhand offhandEmergency =
                CombatInventoryAutomation26.desiredOffhand(
                        true,
                        true,
                        14.0F,
                        configuration
                );

        assertEquals(
                CombatInventoryAutomation26.InventoryAction.AUTO_TOTEM,
                autoTotem.action()
        );
        assertEquals(
                CombatInventoryAutomation26.OffhandItem.TOTEM,
                autoTotem.item()
        );
        assertFalse(autoTotem.replaceOffhand());
        assertEquals(11, autoTotem.cooldownTicks());

        assertEquals(
                CombatInventoryAutomation26.InventoryAction.OFFHAND,
                offhandEmergency.action()
        );
        assertEquals(
                CombatInventoryAutomation26.OffhandItem.TOTEM,
                offhandEmergency.item()
        );
        assertTrue(offhandEmergency.replaceOffhand());
        assertEquals(2, offhandEmergency.cooldownTicks());
    }

    @Test
    void actionCooldownsAreIndependentWhenTheSelectedModuleChanges() {
        CombatInventoryAutomation26.InventoryCooldowns cooldowns =
                new CombatInventoryAutomation26.InventoryCooldowns();

        cooldowns.start(
                CombatInventoryAutomation26.InventoryAction.OFFHAND,
                7
        );
        assertFalse(cooldowns.ready(
                CombatInventoryAutomation26.InventoryAction.OFFHAND
        ));
        assertTrue(cooldowns.ready(
                CombatInventoryAutomation26.InventoryAction.AUTO_TOTEM
        ));

        cooldowns.start(
                CombatInventoryAutomation26.InventoryAction.AUTO_TOTEM,
                2
        );
        cooldowns.advance();
        assertEquals(
                1,
                cooldowns.remainingTicks(
                        CombatInventoryAutomation26.InventoryAction.AUTO_TOTEM
                )
        );
        assertEquals(
                6,
                cooldowns.remainingTicks(
                        CombatInventoryAutomation26.InventoryAction.OFFHAND
                )
        );
    }

    @Test
    void inventorySelectionAvoidsSelectedHotbarAndPrefersMainInventory() {
        int selected = CombatInventoryAutomation26.selectInventorySource(List.of(
                new CombatInventoryAutomation26.ItemCandidate(0, 64, true, true),
                new CombatInventoryAutomation26.ItemCandidate(2, 32, false, true),
                new CombatInventoryAutomation26.ItemCandidate(12, 1, false, true),
                new CombatInventoryAutomation26.ItemCandidate(14, 4, false, true)
        ));

        assertEquals(14, selected);
    }

    @Test
    void inventorySelectionRejectsInvalidCandidatesAndIsStableOnTies() {
        int selected = CombatInventoryAutomation26.selectInventorySource(List.of(
                new CombatInventoryAutomation26.ItemCandidate(-1, 64, false, true),
                new CombatInventoryAutomation26.ItemCandidate(36, 64, false, true),
                new CombatInventoryAutomation26.ItemCandidate(9, 0, false, true),
                new CombatInventoryAutomation26.ItemCandidate(12, 4, false, false),
                new CombatInventoryAutomation26.ItemCandidate(11, 4, false, true),
                new CombatInventoryAutomation26.ItemCandidate(10, 4, false, true)
        ));

        assertEquals(10, selected);
        assertEquals(
                -1,
                CombatInventoryAutomation26.selectInventorySource(null)
        );
    }

    @Test
    void weaponSelectionPrefersDamageThenSpeedAndIsOrderIndependent() {
        var weak = new CombatInventoryAutomation26.WeaponCandidate(
                0, true, 100, 4.0, -2.4
        );
        var axe = new CombatInventoryAutomation26.WeaponCandidate(
                3, true, 80, 9.0, -3.0
        );
        var sword = new CombatInventoryAutomation26.WeaponCandidate(
                5, true, 120, 7.0, -2.4
        );

        assertEquals(
                3,
                CombatInventoryAutomation26.selectBestWeapon(
                        List.of(weak, axe, sword),
                        0,
                        3
                )
        );
        assertEquals(
                3,
                CombatInventoryAutomation26.selectBestWeapon(
                        List.of(sword, weak, axe),
                        0,
                        3
                )
        );
    }

    @Test
    void weaponSelectionKeepsSelectedSlotOnExactTie() {
        var slotTwo = new CombatInventoryAutomation26.WeaponCandidate(
                2, true, 100, 7.0, -2.4
        );
        var selected = new CombatInventoryAutomation26.WeaponCandidate(
                6, true, 20, 7.0, -2.4
        );

        assertEquals(
                6,
                CombatInventoryAutomation26.selectBestWeapon(
                        List.of(slotTwo, selected),
                        6,
                        3
                )
        );
    }

    @Test
    void weaponSelectionRejectsBrokenNonMeleeAndMalformedCandidates() {
        assertEquals(
                -1,
                CombatInventoryAutomation26.selectBestWeapon(
                        List.of(
                                new CombatInventoryAutomation26.WeaponCandidate(
                                        0, true, 3, 10.0, -3.0
                                ),
                                new CombatInventoryAutomation26.WeaponCandidate(
                                        1, false, 100, 20.0, 1.0
                                ),
                                new CombatInventoryAutomation26.WeaponCandidate(
                                        2, true, 100, Double.NaN, 1.0
                                ),
                                new CombatInventoryAutomation26.WeaponCandidate(
                                        20, true, 100, 20.0, 1.0
                                )
                        ),
                        0,
                        3
                )
        );
        assertEquals(
                -1,
                CombatInventoryAutomation26.selectBestWeapon(null, 0, 3)
        );
    }

    @Test
    void cooldownIsBoundedAndOnlyAdvancesOneTickAtATime() {
        CombatInventoryAutomation26.BoundedCooldown cooldown =
                new CombatInventoryAutomation26.BoundedCooldown();

        assertTrue(cooldown.ready());
        cooldown.start(99);
        assertEquals(20, cooldown.remainingTicks());
        cooldown.advance();
        assertEquals(19, cooldown.remainingTicks());
        assertFalse(cooldown.ready());
        for (int tick = 0; tick < 19; tick++) {
            cooldown.advance();
        }
        assertTrue(cooldown.ready());
    }

    @Test
    void hotbarLeaseRestoresOnlyTheSlotItStillOwns() {
        CombatInventoryAutomation26.HotbarLease lease =
                new CombatInventoryAutomation26.HotbarLease();

        var switchPlan = lease.previewSwitch(1, 5, "auto_weapon", 55);
        assertEquals(5, switchPlan.slot());
        lease.commitSwitch(1, 5, "auto_weapon");
        assertEquals(1, lease.restoreSlotIfOwned(5));
        assertEquals(-1, lease.restoreSlotIfOwned(4));

        var restorePlan = lease.previewRestore(5);
        assertEquals(1, restorePlan.slot());
        assertTrue(restorePlan.restore());
        lease.commitRestore();
        assertFalse(lease.active());
    }

    @Test
    void hotbarLeaseYieldsToManualSlotOverrideUntilInputGoesIdle() {
        CombatInventoryAutomation26.HotbarLease lease =
                new CombatInventoryAutomation26.HotbarLease();
        lease.commitSwitch(1, 5, "auto_weapon");

        assertNull(lease.previewSwitch(4, 6, "auto_weapon", 55));
        assertTrue(lease.suppressedUntilIdle());
        assertNull(lease.previewSwitch(4, 6, "auto_weapon", 55));
        assertNull(lease.previewRestore(4));
        assertFalse(lease.suppressedUntilIdle());
        assertEquals(
                6,
                lease.previewSwitch(4, 6, "auto_weapon", 55).slot()
        );
    }

    @Test
    void releaseCannotRestoreAHotbarBeforeAPlayerExists() {
        assertFalse(CombatInventoryAutomation26.canRestoreHotbar(null, null));
    }

    @Test
    void inventorySlotMappingMatchesTheVanillaPlayerMenu() {
        assertEquals(36, CombatInventoryAutomation26.inventoryIndexToMenuSlot(0));
        assertEquals(44, CombatInventoryAutomation26.inventoryIndexToMenuSlot(8));
        assertEquals(9, CombatInventoryAutomation26.inventoryIndexToMenuSlot(9));
        assertEquals(35, CombatInventoryAutomation26.inventoryIndexToMenuSlot(35));
        assertThrows(
                IllegalArgumentException.class,
                () -> CombatInventoryAutomation26.inventoryIndexToMenuSlot(36)
        );
    }

    @Test
    void configurationRejectsUnboundedMutationSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatInventoryAutomation26.Configuration(
                        20.0F,
                        16.0F,
                        CombatInventoryAutomation26.OffhandItem.END_CRYSTAL,
                        true,
                        true,
                        0,
                        3
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatInventoryAutomation26.Configuration(
                        20.0F,
                        16.0F,
                        CombatInventoryAutomation26.OffhandItem.END_CRYSTAL,
                        true,
                        true,
                        3,
                        101
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatInventoryAutomation26.Configuration(
                        20.0F,
                        16.0F,
                        CombatInventoryAutomation26.OffhandItem.END_CRYSTAL,
                        true,
                        true,
                        3,
                        true,
                        21,
                        3
                )
        );
    }
}
