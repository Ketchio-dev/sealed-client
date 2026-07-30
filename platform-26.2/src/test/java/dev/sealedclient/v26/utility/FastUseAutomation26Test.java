package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastUseAutomation26Test {
    @Test
    void cooldownLeaseRequiresTheExactSafeWhitelistedContext() {
        assertTrue(valid(
                true,
                true,
                true,
                true,
                true
        ));

        // XP bottle -> food/block/other item.
        assertFalse(valid(
                true,
                true,
                true,
                true,
                false
        ));
        // Slot changed even if a superficially similar stack is present.
        assertFalse(valid(
                true,
                true,
                true,
                false,
                true
        ));
        // Key release.
        assertFalse(valid(
                true,
                true,
                false,
                true,
                true
        ));
        // Runtime safety block.
        assertFalse(valid(
                false,
                true,
                true,
                true,
                true
        ));
        // Screen opened.
        assertFalse(valid(
                true,
                false,
                true,
                true,
                true
        ));
        // Looking at a block/entity could produce a non-item interaction.
        assertFalse(FastUseAutomation26.leaseContextValid(
                true,
                true,
                true,
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true
        ));
    }

    @Test
    void actualUseBoundaryBlocksOnlyAnUnsafeActiveLease() {
        assertFalse(FastUseAutomation26.shouldBlockLeasedUse(
                false,
                false,
                true
        ));
        assertFalse(FastUseAutomation26.shouldBlockLeasedUse(
                true,
                true,
                true
        ));
        assertTrue(FastUseAutomation26.shouldBlockLeasedUse(
                true,
                false,
                true
        ));
        assertFalse(FastUseAutomation26.shouldBlockLeasedUse(
                true,
                false,
                false
        ));
    }

    private static boolean valid(
            boolean safetyReady,
            boolean screenClear,
            boolean keyDown,
            boolean sameSlot,
            boolean allowedItem
    ) {
        return FastUseAutomation26.leaseContextValid(
                true,
                safetyReady,
                true,
                screenClear,
                true,
                true,
                keyDown,
                true,
                sameSlot,
                true,
                true,
                allowedItem
        );
    }
}
