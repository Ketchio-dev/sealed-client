package dev.sealedclient.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTransactionEngineTest {
    @Test
    void confirmsSuccessfulAction() {
        CombatTransactionEngine<Long> engine = new CombatTransactionEngine<>(4, 2, 2);

        assertTrue(engine.begin(CombatTransactionEngine.Action.PLACE, 42L, 10));
        assertFalse(engine.begin(CombatTransactionEngine.Action.BREAK, 99L, 10));
        assertEquals(
                CombatTransactionEngine.Confirmation.ACCEPTED,
                engine.confirm(CombatTransactionEngine.Action.PLACE, 42L, 12)
        );

        CombatTransactionEngine.Snapshot<Long> status = engine.snapshot();
        assertEquals(CombatTransactionEngine.Phase.CONFIRMED, status.phase());
        assertEquals(1, status.attempts());
        assertTrue(status.concise().contains("confirmed"));
    }

    @Test
    void acceptsDelayedConfirmationDuringBackoff() {
        CombatTransactionEngine<Integer> engine = new CombatTransactionEngine<>(3, 1, 2);
        engine.begin(CombatTransactionEngine.Action.BREAK, 91, 0);

        assertEquals(CombatTransactionEngine.Directive.NONE, engine.advance(3));
        assertEquals(CombatTransactionEngine.Phase.RETRY_BACKOFF, engine.snapshot().phase());
        assertEquals(
                CombatTransactionEngine.Confirmation.ACCEPTED,
                engine.confirm(CombatTransactionEngine.Action.BREAK, 91, 4)
        );
        assertEquals(CombatTransactionEngine.Directive.NONE, engine.advance(5));
        assertEquals(1, engine.snapshot().attempts());
    }

    @Test
    void ignoresMismatchedAndDeduplicatesMatchingPackets() {
        CombatTransactionEngine<Integer> engine = new CombatTransactionEngine<>(5, 1, 1);
        engine.begin(CombatTransactionEngine.Action.BREAK, 7, 0);

        assertEquals(
                CombatTransactionEngine.Confirmation.IGNORED,
                engine.confirm(CombatTransactionEngine.Action.BREAK, 8, 1)
        );
        assertEquals(
                CombatTransactionEngine.Confirmation.ACCEPTED,
                engine.confirm(CombatTransactionEngine.Action.BREAK, 7, 2)
        );
        assertEquals(
                CombatTransactionEngine.Confirmation.DUPLICATE,
                engine.confirm(CombatTransactionEngine.Action.BREAK, 7, 2)
        );
        assertEquals(CombatTransactionEngine.Phase.CONFIRMED, engine.snapshot().phase());
    }

    @Test
    void appliesBoundedBackoffAndExhaustsRetries() {
        CombatTransactionEngine<String> engine = new CombatTransactionEngine<>(2, 2, 1);
        engine.begin(CombatTransactionEngine.Action.PLACE, "base", 0);

        assertEquals(CombatTransactionEngine.Directive.NONE, engine.advance(2));
        assertEquals(CombatTransactionEngine.Phase.RETRY_BACKOFF, engine.snapshot().phase());
        assertEquals(CombatTransactionEngine.Directive.RETRY, engine.advance(3));
        assertEquals(2, engine.snapshot().attempts());

        assertEquals(CombatTransactionEngine.Directive.NONE, engine.advance(5));
        assertEquals(CombatTransactionEngine.Directive.NONE, engine.advance(6));
        assertEquals(CombatTransactionEngine.Directive.RETRY, engine.advance(7));
        assertEquals(3, engine.snapshot().attempts());

        assertEquals(CombatTransactionEngine.Directive.FAILED, engine.advance(9));
        assertEquals(CombatTransactionEngine.Phase.FAILED, engine.snapshot().phase());
        assertEquals(3, engine.snapshot().attempts());
        assertEquals(CombatTransactionEngine.Directive.NONE, engine.advance(10_000));
    }

    @Test
    void oneRetryDirectiveIsEmittedPerAttempt() {
        CombatTransactionEngine<String> engine = new CombatTransactionEngine<>(1, 1, 1);
        engine.begin(CombatTransactionEngine.Action.BREAK, "crystal", 0);

        engine.advance(1);
        assertEquals(CombatTransactionEngine.Directive.RETRY, engine.advance(2));
        assertEquals(CombatTransactionEngine.Directive.NONE, engine.advance(2));
        assertEquals(2, engine.snapshot().attempts());
    }

    @Test
    void disconnectResetClearsPendingIdentityAndAllowsNewAction() {
        CombatTransactionEngine<Long> engine = new CombatTransactionEngine<>(3, 2, 1);
        engine.begin(CombatTransactionEngine.Action.PLACE, 123L, 50);

        engine.reset("disconnect", 51);

        CombatTransactionEngine.Snapshot<Long> status = engine.snapshot();
        assertEquals(CombatTransactionEngine.Phase.IDLE, status.phase());
        assertNull(status.action());
        assertNull(status.key());
        assertEquals(0, status.attempts());
        assertEquals("disconnect", status.detail());
        assertFalse(status.terminal());
        assertTrue(engine.begin(CombatTransactionEngine.Action.BREAK, 999L, 52));
    }
}
