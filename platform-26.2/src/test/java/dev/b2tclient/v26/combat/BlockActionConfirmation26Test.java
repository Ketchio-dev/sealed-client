package dev.b2tclient.v26.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockActionConfirmation26Test {
    @Test
    void placeUseBreakAndChargeRequireExactTransaction() {
        for (BlockActionConfirmation26.Action action
                : BlockActionConfirmation26.Action.values()) {
            BlockActionConfirmation26 state =
                    new BlockActionConfirmation26(4, 1);
            assertTrue(state.begin(action, 42L, 10L));
            assertFalse(state.confirm(action, 41L));
            assertTrue(state.confirm(action, 42L));
            assertEquals(
                    BlockActionConfirmation26.Phase.CONFIRMED,
                    state.snapshot().phase()
            );
        }
    }

    @Test
    void retryWaitsForActualSendAndThenFailsBoundedly() {
        BlockActionConfirmation26 state =
                new BlockActionConfirmation26(3, 1);
        assertTrue(state.begin(
                BlockActionConfirmation26.Action.USE,
                7L,
                0L
        ));
        assertEquals(
                BlockActionConfirmation26.Directive.RETRY,
                state.advance(3L)
        );
        assertEquals(
                BlockActionConfirmation26.Directive.RETRY,
                state.advance(100L)
        );
        assertEquals(0, state.snapshot().retries());

        assertTrue(state.markRetried(100L));
        assertEquals(
                BlockActionConfirmation26.Directive.FAILED,
                state.advance(103L)
        );
        assertEquals(
                BlockActionConfirmation26.Phase.FAILED,
                state.snapshot().phase()
        );
    }

    @Test
    void resetDoesNotLeakThePriorBlockTransaction() {
        BlockActionConfirmation26 state =
                new BlockActionConfirmation26(2, 0);
        assertTrue(state.begin(
                BlockActionConfirmation26.Action.BREAK,
                1L,
                0L
        ));
        state.reset();
        assertEquals(
                BlockActionConfirmation26.Phase.IDLE,
                state.snapshot().phase()
        );
        assertTrue(state.begin(
                BlockActionConfirmation26.Action.CHARGE,
                2L,
                5L
        ));
        assertEquals(0, state.snapshot().retries());
    }

    @Test
    void explicitFailureIsStickyUntilReset() {
        BlockActionConfirmation26 state =
                new BlockActionConfirmation26(2, 1);
        assertTrue(state.begin(
                BlockActionConfirmation26.Action.PLACE,
                4L,
                0L
        ));
        state.fail();
        assertEquals(
                BlockActionConfirmation26.Phase.FAILED,
                state.snapshot().phase()
        );
        assertFalse(state.confirm(
                BlockActionConfirmation26.Action.PLACE,
                4L
        ));
    }
}
