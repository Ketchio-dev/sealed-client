package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationState26Test {
    @Test
    void retryIsNotConsumedUntilActionActuallyExecutes() {
        ConfirmationState26 state = new ConfirmationState26(3, 1);
        assertTrue(state.begin(ConfirmationState26.Action.PLACE, 42L, 10L));

        assertEquals(
                ConfirmationState26.Directive.NONE,
                state.advance(12L)
        );
        assertEquals(
                ConfirmationState26.Directive.RETRY,
                state.advance(13L)
        );
        assertEquals(
                ConfirmationState26.Directive.RETRY,
                state.advance(20L)
        );
        assertEquals(0, state.snapshot().retries());

        assertTrue(state.markRetried(20L));
        assertEquals(1, state.snapshot().retries());
        assertEquals(
                ConfirmationState26.Directive.FAILED,
                state.advance(23L)
        );
        assertEquals(
                ConfirmationState26.Phase.FAILED,
                state.snapshot().phase()
        );
    }

    @Test
    void confirmationRequiresExactActionAndIdentity() {
        ConfirmationState26 state = new ConfirmationState26(4, 1);
        assertTrue(state.begin(ConfirmationState26.Action.BREAK, 7L, 0L));

        assertFalse(state.confirm(ConfirmationState26.Action.PLACE, 7L));
        assertFalse(state.confirm(ConfirmationState26.Action.BREAK, 8L));
        assertTrue(state.confirm(ConfirmationState26.Action.BREAK, 7L));
        assertEquals(
                ConfirmationState26.Phase.CONFIRMED,
                state.snapshot().phase()
        );
    }

    @Test
    void resetAllowsASeparateTransactionWithoutStateLeakage() {
        ConfirmationState26 state = new ConfirmationState26(2, 0);
        assertTrue(state.begin(ConfirmationState26.Action.BREAK, 1L, 0L));
        state.reset();

        assertEquals(
                ConfirmationState26.Phase.IDLE,
                state.snapshot().phase()
        );
        assertTrue(state.begin(ConfirmationState26.Action.PLACE, 2L, 5L));
        assertEquals(0, state.snapshot().retries());
    }
}
