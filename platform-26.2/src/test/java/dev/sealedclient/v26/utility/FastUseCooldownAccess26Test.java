package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastUseCooldownAccess26Test {
    @Test
    void vanillaCooldownFieldIsNamedRightClickDelayOn26() throws Exception {
        java.lang.reflect.Field field =
                net.minecraft.client.Minecraft.class.getDeclaredField(
                        "rightClickDelay"
                );
        assertEquals(int.class, field.getType());
    }

    @Test
    void accessFailsClosedWithoutTheAppliedMixin() {
        assertFalse(FastUseCooldownAccess26.available(null));
        assertEquals(-1, FastUseCooldownAccess26.current(null));
        assertFalse(FastUseCooldownAccess26.restore(null, 4));
    }

    @Test
    void limiterCanOnlyReduceTheVanillaDelay() {
        assertEquals(2, FastUseCooldownAccess26.limitedValue(4, 2));
        assertEquals(1, FastUseCooldownAccess26.limitedValue(1, 2));
        assertEquals(-1, FastUseCooldownAccess26.limitedValue(-1, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> FastUseCooldownAccess26.limit(null, 0)
        );
    }

    @Test
    void restorationPreservesTheRemainingVanillaBaseline() {
        assertEquals(
                4,
                FastUseCooldownAccess26.restoredValue(2, 4, 0)
        );
        assertEquals(
                3,
                FastUseCooldownAccess26.restoredValue(1, 4, 1)
        );
        assertEquals(
                5,
                FastUseCooldownAccess26.restoredValue(5, 4, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> FastUseCooldownAccess26.restoredValue(1, 4, -1)
        );
    }
}
