package dev.sealedclient.v26.utility;

import dev.sealedclient.v26.combat.CombatActionArbiter26;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChestSwapAutomation26Test {
    @Test
    void claimsTheCompleteInventoryHotbarUseBundle() {
        assertEquals(
                Set.of(
                        CombatActionArbiter26.Channel.INVENTORY,
                        CombatActionArbiter26.Channel.HOTBAR,
                        CombatActionArbiter26.Channel.USE
                ),
                ChestSwapAutomation26.INVENTORY_CHANNELS
        );
    }

    @Test
    void inventoryMenuMappingMatchesVanillaPlayerInventory() {
        assertEquals(
                36,
                ChestSwapAutomation26.inventoryIndexToMenuSlot(0)
        );
        assertEquals(
                44,
                ChestSwapAutomation26.inventoryIndexToMenuSlot(8)
        );
        assertEquals(
                9,
                ChestSwapAutomation26.inventoryIndexToMenuSlot(9)
        );
        assertEquals(
                35,
                ChestSwapAutomation26.inventoryIndexToMenuSlot(35)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ChestSwapAutomation26.inventoryIndexToMenuSlot(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ChestSwapAutomation26.inventoryIndexToMenuSlot(36)
        );
    }

    @Test
    void recoveryOnlyTouchesAnExactEmptyCapturedSource() {
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.ROLL_BACK,
                ChestSwapAutomation26.classifyRecovery(observation(
                        true,
                        true,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false
                ))
        );
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.COMPLETE_SWAP,
                ChestSwapAutomation26.classifyRecovery(observation(
                        true,
                        true,
                        false,
                        false,
                        true,
                        true,
                        false,
                        false,
                        false
                ))
        );
    }

    @Test
    void recoveryRecognizesAlreadyTerminalStatesWithoutClicking() {
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.ALREADY_APPLIED,
                ChestSwapAutomation26.classifyRecovery(observation(
                        true,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        true,
                        false
                ))
        );
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.ALREADY_ROLLED_BACK,
                ChestSwapAutomation26.classifyRecovery(observation(
                        true,
                        false,
                        true,
                        false,
                        false,
                        false,
                        true,
                        false,
                        true
                ))
        );
    }

    @Test
    void recoveryAbandonsUnknownManualOrSessionState() {
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.ABANDON,
                ChestSwapAutomation26.classifyRecovery(observation(
                        false,
                        true,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false
                ))
        );
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.ABANDON,
                ChestSwapAutomation26.classifyRecovery(observation(
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false
                ))
        );
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.ABANDON,
                ChestSwapAutomation26.classifyRecovery(observation(
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false
                ))
        );
        assertEquals(
                ChestSwapAutomation26.RecoveryAction.ABANDON,
                ChestSwapAutomation26.classifyRecovery(null)
        );
    }

    @Test
    void configurationEnforcesBoundedCooldowns() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChestSwapAutomation26.Configuration(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChestSwapAutomation26.Configuration(
                        10,
                        21,
                        8,
                        4
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChestSwapAutomation26.Configuration(
                        10,
                        4,
                        41,
                        4
                )
        );
    }

    @Test
    void sessionIdentityIncludesConnectionAndInventoryMenuIdentity() {
        Object player = new Object();
        Object level = new Object();
        Object connection = new Object();
        Object inventoryMenu = new Object();
        var original = new ChestSwapAutomation26.SessionIdentity(
                player,
                level,
                connection,
                inventoryMenu
        );

        assertEquals(
                true,
                ChestSwapAutomation26.SessionIdentity.same(
                        original,
                        new ChestSwapAutomation26.SessionIdentity(
                                player,
                                level,
                                connection,
                                inventoryMenu
                        )
                )
        );
        assertEquals(
                false,
                ChestSwapAutomation26.SessionIdentity.same(
                        original,
                        new ChestSwapAutomation26.SessionIdentity(
                                player,
                                level,
                                new Object(),
                                inventoryMenu
                        )
                )
        );
        assertEquals(
                false,
                ChestSwapAutomation26.SessionIdentity.same(
                        original,
                        new ChestSwapAutomation26.SessionIdentity(
                                player,
                                level,
                                connection,
                                new Object()
                        )
                )
        );
    }

    @Test
    void preparedContextRejectsUnrelatedMenuStateMutation() {
        Object player = new Object();
        Object level = new Object();
        Object connection = new Object();
        Object inventoryMenu = new Object();
        var prepared = new ChestSwapAutomation26.PreparedContextIdentity(
                player,
                level,
                connection,
                inventoryMenu,
                7,
                31,
                900,
                4
        );

        assertEquals(
                true,
                ChestSwapAutomation26.PreparedContextIdentity.same(
                        new ChestSwapAutomation26.PreparedContextIdentity(
                                player,
                                level,
                                connection,
                                inventoryMenu,
                                7,
                                31,
                                900,
                                4
                        ),
                        prepared
                )
        );
        assertEquals(
                false,
                ChestSwapAutomation26.PreparedContextIdentity.same(
                        new ChestSwapAutomation26.PreparedContextIdentity(
                                player,
                                level,
                                connection,
                                inventoryMenu,
                                7,
                                32,
                                900,
                                4
                        ),
                        prepared
                )
        );
        assertEquals(
                false,
                ChestSwapAutomation26.PreparedContextIdentity.same(
                        new ChestSwapAutomation26.PreparedContextIdentity(
                                player,
                                level,
                                connection,
                                inventoryMenu,
                                7,
                                31,
                                901,
                                4
                        ),
                        prepared
                )
        );
        assertEquals(
                false,
                ChestSwapAutomation26.PreparedContextIdentity.same(
                        new ChestSwapAutomation26.PreparedContextIdentity(
                                player,
                                level,
                                connection,
                                inventoryMenu,
                                7,
                                31,
                                900,
                                5
                        ),
                        prepared
                )
        );
    }

    @Test
    void sessionEpochIsMonotonicAndSkipsReservedSentinel() {
        assertEquals(1L, ChestSwapAutomation26.nextSessionEpoch(0L));
        assertEquals(
                Long.MIN_VALUE + 1L,
                ChestSwapAutomation26.nextSessionEpoch(Long.MAX_VALUE)
        );
    }

    @Test
    void sessionTransitionClearsTickAndTransactionDiagnostics() {
        Object oldConnection = new Object();
        Object newConnection = new Object();
        Object player = new Object();
        Object level = new Object();
        Object menu = new Object();
        var previous = new ChestSwapAutomation26.SessionIdentity(
                player,
                level,
                oldConnection,
                menu
        );
        var current = new ChestSwapAutomation26.SessionIdentity(
                player,
                level,
                newConnection,
                menu
        );

        ChestSwapAutomation26.SessionTransition transition =
                ChestSwapAutomation26.transitionSession(
                        previous,
                        current,
                        9L,
                        240,
                        3,
                        ChestSwapAutomation26.TransactionResult.APPLIED
                );

        assertEquals(true, transition.changed());
        assertEquals(10L, transition.epoch());
        assertEquals(
                Integer.MIN_VALUE,
                transition.lastLogicalTransactionTick()
        );
        assertEquals(0, transition.lastPhysicalClicks());
        assertEquals(
                ChestSwapAutomation26.TransactionResult.NONE,
                transition.lastTransactionResult()
        );
    }

    @Test
    void unchangedSessionPreservesTransactionDiagnostics() {
        Object player = new Object();
        Object level = new Object();
        Object connection = new Object();
        Object menu = new Object();
        var identity = new ChestSwapAutomation26.SessionIdentity(
                player,
                level,
                connection,
                menu
        );

        ChestSwapAutomation26.SessionTransition transition =
                ChestSwapAutomation26.transitionSession(
                        identity,
                        new ChestSwapAutomation26.SessionIdentity(
                                player,
                                level,
                                connection,
                                menu
                        ),
                        9L,
                        240,
                        3,
                        ChestSwapAutomation26.TransactionResult.APPLIED
                );

        assertEquals(false, transition.changed());
        assertEquals(9L, transition.epoch());
        assertEquals(240, transition.lastLogicalTransactionTick());
        assertEquals(3, transition.lastPhysicalClicks());
        assertEquals(
                ChestSwapAutomation26.TransactionResult.APPLIED,
                transition.lastTransactionResult()
        );
    }

    private static ChestSwapAutomation26.RecoveryObservation observation(
            boolean contextValid,
            boolean sourceEmpty,
            boolean cursorEmpty,
            boolean cursorMatchesSource,
            boolean cursorMatchesOriginal,
            boolean chestMatchesSource,
            boolean chestMatchesOriginal,
            boolean finalState,
            boolean initialState
    ) {
        return new ChestSwapAutomation26.RecoveryObservation(
                contextValid,
                sourceEmpty,
                cursorEmpty,
                cursorMatchesSource,
                cursorMatchesOriginal,
                chestMatchesSource,
                chestMatchesOriginal,
                finalState,
                initialState
        );
    }
}
