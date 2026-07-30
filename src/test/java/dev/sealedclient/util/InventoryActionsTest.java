package dev.sealedclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryActionsTest {
    @Test
    void mapsMainInventoryAndHotbarSlots() {
        assertEquals(36, InventoryActions.inventoryIndexToMenuSlot(0));
        assertEquals(44, InventoryActions.inventoryIndexToMenuSlot(8));
        assertEquals(9, InventoryActions.inventoryIndexToMenuSlot(9));
        assertEquals(35, InventoryActions.inventoryIndexToMenuSlot(35));
    }

    @Test
    void rejectsNonMainInventorySlots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryActions.inventoryIndexToMenuSlot(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> InventoryActions.inventoryIndexToMenuSlot(36)
        );
    }
}
