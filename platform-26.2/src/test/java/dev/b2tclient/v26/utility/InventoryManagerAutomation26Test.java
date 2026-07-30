package dev.b2tclient.v26.utility;

import dev.b2tclient.v26.combat.CombatActionArbiter26;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryManagerAutomation26Test {
    @Test
    void claimsAtomicInventoryHotbarUseBundle() {
        assertEquals(
                Set.of(
                        CombatActionArbiter26.Channel.INVENTORY,
                        CombatActionArbiter26.Channel.HOTBAR,
                        CombatActionArbiter26.Channel.USE
                ),
                InventoryManagerAutomation26.INVENTORY_CHANNELS
        );
        assertEquals(10, InventoryManagerAutomation26.PRIORITY);
    }

    @Test
    void duplicateSubmitGuardRequiresTheSameIdentityAndTick() {
        Object player = new Object();

        assertTrue(InventoryManagerAutomation26.sameIdentityTick(
                player,
                20,
                player,
                20
        ));
        assertFalse(InventoryManagerAutomation26.sameIdentityTick(
                player,
                20,
                new Object(),
                20
        ));
        assertFalse(InventoryManagerAutomation26.sameIdentityTick(
                player,
                21,
                player,
                20
        ));
        assertFalse(InventoryManagerAutomation26.sameIdentityTick(
                null,
                20,
                null,
                20
        ));
    }

    @Test
    void deniedArbitrationLeavesDecisionImmediatelyRetryable() {
        InventoryManagerDecisionEngine26 engine =
                new InventoryManagerDecisionEngine26();
        var observation =
                new InventoryManagerDecisionEngine26.Observation(
                        41L,
                        true,
                        true,
                        true,
                        true,
                        false,
                        List.of(
                                new InventoryManagerDecisionEngine26.Candidate(
                                        9,
                                        40,
                                        64,
                                        true,
                                        "stone"
                                ),
                                new InventoryManagerDecisionEngine26.Candidate(
                                        10,
                                        16,
                                        64,
                                        true,
                                        "stone"
                                )
                        )
                );
        engine.step(observation);
        var prepared = engine.step(observation);

        CombatActionArbiter26 arbiter = new CombatActionArbiter26();
        arbiter.beginTick(CombatActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                "combat_inventory",
                100,
                InventoryManagerAutomation26.INVENTORY_CHANNELS
        );
        arbiter.submit(
                InventoryManagerAutomation26.OWNER,
                InventoryManagerAutomation26.PRIORITY,
                InventoryManagerAutomation26.INVENTORY_CHANNELS
        );
        arbiter.resolve();

        assertFalse(arbiter.ownsAll(
                InventoryManagerAutomation26.OWNER,
                InventoryManagerAutomation26.INVENTORY_CHANNELS
        ));
        engine.commit(prepared, false);
        assertEquals(0, engine.snapshot().cooldownTicks());
        assertTrue(engine.step(observation).apply());
    }

    @Test
    void emptyFingerprintRecognizesOnlyEmptyState() {
        InventoryManagerAutomation26.StackFingerprint fingerprint =
                InventoryManagerAutomation26.StackFingerprint.of(
                        ItemStack.EMPTY
                );

        assertTrue(fingerprint.matches(ItemStack.EMPTY));
        assertTrue(fingerprint.matches(null));
    }

    @Test
    void pureFingerprintContractRequiresExactCountAndComponents() {
        assertTrue(InventoryManagerAutomation26.fingerprintMatches(
                false,
                12,
                false,
                12,
                true
        ));
        assertFalse(InventoryManagerAutomation26.fingerprintMatches(
                false,
                12,
                false,
                11,
                true
        ));
        assertFalse(InventoryManagerAutomation26.fingerprintMatches(
                false,
                12,
                false,
                12,
                false
        ));
        assertFalse(InventoryManagerAutomation26.fingerprintMatches(
                false,
                12,
                true,
                0,
                true
        ));
        assertTrue(InventoryManagerAutomation26.fingerprintMatches(
                true,
                0,
                true,
                0,
                false
        ));
        assertFalse(InventoryManagerAutomation26.fingerprintMatches(
                true,
                0,
                false,
                1,
                false
        ));
    }

    @Test
    void recoveryAcceptsOnlyExactFullSourceAndEmptyOriginalSlot() {
        assertTrue(InventoryManagerAutomation26.recoveryEligible(
                true,
                true
        ));
        assertFalse(InventoryManagerAutomation26.recoveryEligible(
                false,
                true
        ), "partial carried source must be rejected");
        assertFalse(InventoryManagerAutomation26.recoveryEligible(
                true,
                false
        ), "occupied original source must be rejected");
    }

    @Test
    void mergeDecisionCarriesExactCountsForLiveFingerprintChecks() {
        InventoryManagerDecisionEngine26.Merge merge =
                InventoryManagerDecisionEngine26.selectMerge(List.of(
                        new InventoryManagerDecisionEngine26.Candidate(
                                9,
                                40,
                                64,
                                true,
                                "exact-components-a"
                        ),
                        new InventoryManagerDecisionEngine26.Candidate(
                                10,
                                16,
                                64,
                                true,
                                "exact-components-a"
                        )
                )).orElseThrow();

        assertEquals(16, merge.sourceCount());
        assertEquals(40, merge.targetCount());
        assertEquals(56, merge.sourceCount() + merge.targetCount());
        assertEquals("exact-components-a", merge.equivalenceGroup());
    }

    @Test
    void liveMergeValidationRejectsMissingStacksFailClosed() {
        InventoryManagerDecisionEngine26.Merge merge =
                new InventoryManagerDecisionEngine26.Merge(
                        10,
                        9,
                        16,
                        40,
                        64,
                        "stack-0"
                );

        assertFalse(InventoryManagerAutomation26.validMergeStacks(
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                merge
        ));
        assertFalse(InventoryManagerAutomation26.validMergeStacks(
                null,
                ItemStack.EMPTY,
                merge
        ));
    }

    @Test
    void inventoryMappingMatchesVanillaPlayerMenuExactly() {
        assertEquals(
                36,
                InventoryManagerAutomation26.inventoryIndexToMenuSlot(0)
        );
        assertEquals(
                44,
                InventoryManagerAutomation26.inventoryIndexToMenuSlot(8)
        );
        assertEquals(
                9,
                InventoryManagerAutomation26.inventoryIndexToMenuSlot(9)
        );
        assertEquals(
                35,
                InventoryManagerAutomation26.inventoryIndexToMenuSlot(35)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryManagerAutomation26
                        .inventoryIndexToMenuSlot(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryManagerAutomation26
                        .inventoryIndexToMenuSlot(36)
        );
    }

    @Test
    void configurationIsBoundedAndReleaseClearsLifecycleState() {
        InventoryManagerAutomation26 service =
                new InventoryManagerAutomation26(
                        new InventoryManagerAutomation26.Configuration(40, 2)
                );

        service.release();
        assertFalse(service.status().prepared());
        assertEquals(0, service.status().cooldownTicks());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryManagerAutomation26.Configuration(1, 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryManagerAutomation26.Configuration(8, 41)
        );
    }
}
