package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoArmorAutomation26Test {
    @Test
    void vanillaInventoryAndArmorMenuMappingsAreExact() {
        assertEquals(36, AutoArmorAutomation26.inventoryIndexToMenuSlot(0));
        assertEquals(44, AutoArmorAutomation26.inventoryIndexToMenuSlot(8));
        assertEquals(9, AutoArmorAutomation26.inventoryIndexToMenuSlot(9));
        assertEquals(35, AutoArmorAutomation26.inventoryIndexToMenuSlot(35));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoArmorAutomation26.inventoryIndexToMenuSlot(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoArmorAutomation26.inventoryIndexToMenuSlot(36)
        );

        assertEquals(
                5,
                AutoArmorAutomation26.armorMenuSlot(
                        AutoArmorDecisionEngine26.ArmorSlot.HEAD
                )
        );
        assertEquals(
                6,
                AutoArmorAutomation26.armorMenuSlot(
                        AutoArmorDecisionEngine26.ArmorSlot.CHEST
                )
        );
        assertEquals(
                7,
                AutoArmorAutomation26.armorMenuSlot(
                        AutoArmorDecisionEngine26.ArmorSlot.LEGS
                )
        );
        assertEquals(
                8,
                AutoArmorAutomation26.armorMenuSlot(
                        AutoArmorDecisionEngine26.ArmorSlot.FEET
                )
        );
    }

    @Test
    void transactionStagesRequireExactThreeLocationFingerprints() {
        assertEquals(
                AutoArmorAutomation26.TransactionState.ORIGINAL,
                stage(false, true, true, false, false,
                        false, true, false, false)
        );
        assertEquals(
                AutoArmorAutomation26.TransactionState.SOURCE_ON_CURSOR,
                stage(true, false, false, false, true,
                        false, true, false, false)
        );
        assertEquals(
                AutoArmorAutomation26.TransactionState.DISPLACED_ON_CURSOR,
                stage(true, false, false, true, false,
                        false, false, true, false)
        );
        assertEquals(
                AutoArmorAutomation26.TransactionState.COMPLETED,
                stage(false, true, false, true, false,
                        true, false, false, false)
        );
        assertEquals(
                AutoArmorAutomation26.TransactionState.UNKNOWN,
                stage(true, false, false, false, false,
                        false, true, false, false)
        );
    }

    @Test
    void emptyArmorCompletesAtTheSecondExactStage() {
        assertEquals(
                AutoArmorAutomation26.TransactionState.COMPLETED,
                stage(true, true, false, true, false,
                        true, false, true, true)
        );
    }

    @Test
    void manualTrackerYieldsOnSelectionMenuAndExternalStateChanges() {
        var tracker = new AutoArmorAutomation26.ManualChangeTracker();

        assertFalse(tracker.observe(true, 2, 10));
        assertFalse(tracker.observe(true, 2, 10));
        assertTrue(tracker.observe(true, 3, 10));
        assertTrue(tracker.observe(true, 3, 11));

        tracker.synchronize(true, 3, 12);
        assertFalse(tracker.observe(true, 3, 12));
        assertFalse(tracker.observe(false, 3, -1));
        assertTrue(tracker.observe(true, 3, 13));

        tracker.reset();
        assertFalse(tracker.observe(true, 7, 40));
    }

    @Test
    void eachTransactionStageRequiresThePreparedContext() {
        assertTrue(AutoArmorAutomation26.preparedContextMatches(
                12,
                12,
                80,
                80,
                4,
                4,
                true
        ));
        assertFalse(AutoArmorAutomation26.preparedContextMatches(
                12,
                13,
                80,
                80,
                4,
                4,
                true
        ));
        assertFalse(AutoArmorAutomation26.preparedContextMatches(
                12,
                12,
                80,
                81,
                4,
                4,
                true
        ));
        assertFalse(AutoArmorAutomation26.preparedContextMatches(
                12,
                12,
                80,
                80,
                4,
                5,
                true
        ));
        assertFalse(AutoArmorAutomation26.preparedContextMatches(
                12,
                12,
                80,
                80,
                4,
                4,
                false
        ));
    }

    @Test
    void configurationPreservesLegacyTwoArgumentContract() {
        var configuration =
                new AutoArmorAutomation26.Configuration(false, 7);

        assertFalse(configuration.preserveElytra());
        assertEquals(7, configuration.actionCooldownTicks());
        assertEquals(3, configuration.minimumRemainingDurability());
        assertEquals(2, configuration.manualYieldTicks());
    }

    @Test
    void configurationRejectsUnsafeOrUnboundedValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoArmorAutomation26.Configuration(
                        true,
                        0,
                        3,
                        0.001,
                        2
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoArmorAutomation26.Configuration(
                        true,
                        4,
                        101,
                        0.001,
                        2
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoArmorAutomation26.Configuration(
                        true,
                        4,
                        3,
                        Double.NaN,
                        2
                )
        );
    }

    private static AutoArmorAutomation26.TransactionState stage(
            boolean sourceSlotEmpty,
            boolean cursorEmpty,
            boolean sourceInSourceSlot,
            boolean sourceInArmorSlot,
            boolean sourceOnCursor,
            boolean equippedInSourceSlot,
            boolean equippedInArmorSlot,
            boolean equippedOnCursor,
            boolean equippedOriginallyEmpty
    ) {
        return AutoArmorAutomation26.transactionState(
                new AutoArmorAutomation26.TransactionObservation(
                        sourceSlotEmpty,
                        cursorEmpty,
                        sourceInSourceSlot,
                        sourceInArmorSlot,
                        sourceOnCursor,
                        equippedInSourceSlot,
                        equippedInArmorSlot,
                        equippedOnCursor,
                        equippedOriginallyEmpty
                )
        );
    }
}
