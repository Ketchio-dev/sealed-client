package dev.sealedclient.v26.utility;

import dev.sealedclient.v26.combat.CombatActionArbiter26;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplenishAutomation26Test {
    @Test
    void claimsTheAtomicInventoryHotbarUseBundle() {
        assertEquals(
                Set.of(
                        CombatActionArbiter26.Channel.INVENTORY,
                        CombatActionArbiter26.Channel.HOTBAR,
                        CombatActionArbiter26.Channel.USE
                ),
                ReplenishAutomation26.INVENTORY_CHANNELS
        );
    }

    @Test
    void validatesConfigurationAndProvidesLegacyCompatibleConstructor() {
        ReplenishAutomation26.Configuration configuration =
                new ReplenishAutomation26.Configuration(16, 4);

        assertEquals(16, configuration.threshold());
        assertEquals(4, configuration.delayTicks());
        assertEquals(2, configuration.failureCooldownTicks());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplenishAutomation26.Configuration(0, 4)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplenishAutomation26.Configuration(64, 4)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplenishAutomation26.Configuration(16, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplenishAutomation26.Configuration(16, 4, 21)
        );
    }

    @Test
    void capturedContextRequiresExactStateTickAndSelectedSlot() {
        assertTrue(
                ReplenishAutomation26.capturedContextMatches(
                        7,
                        7,
                        120,
                        120,
                        3,
                        3
                )
        );
        assertFalse(
                ReplenishAutomation26.capturedContextMatches(
                        7,
                        8,
                        120,
                        120,
                        3,
                        3
                )
        );
        assertFalse(
                ReplenishAutomation26.capturedContextMatches(
                        7,
                        7,
                        120,
                        121,
                        3,
                        3
                )
        );
        assertFalse(
                ReplenishAutomation26.capturedContextMatches(
                        7,
                        7,
                        120,
                        120,
                        3,
                        4
                )
        );
        assertFalse(
                ReplenishAutomation26.capturedContextMatches(
                        7,
                        7,
                        120,
                        120,
                        -1,
                        -1
                )
        );
    }

    @Test
    void releaseClearsPendingAndCooldownStateWithoutInventoryMutation() {
        ReplenishAutomation26 service = new ReplenishAutomation26();

        service.release(null);

        ReplenishAutomation26.Status status = service.status();
        assertFalse(status.pending());
        assertEquals(0, status.cooldownTicks());
        assertEquals(Long.MIN_VALUE, status.lastOperationTick());
    }
}
