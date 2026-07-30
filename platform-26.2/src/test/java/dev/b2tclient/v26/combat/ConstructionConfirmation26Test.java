package dev.b2tclient.v26.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionConfirmation26Test {
    @Test
    void requiresExactTransactionAndServerWorldObservation() {
        ConstructionConfirmation26 confirmation =
                new ConstructionConfirmation26(4, 1);
        assertTrue(confirmation.begin(12L, 10L));

        assertEquals(
                ConstructionConfirmation26.Result.NONE,
                confirmation.observe(13L, true, 11L)
        );
        assertEquals(
                ConstructionConfirmation26.Result.WAIT,
                confirmation.observe(12L, false, 12L)
        );
        assertEquals(
                ConstructionConfirmation26.Result.CONFIRMED,
                confirmation.observe(12L, true, 13L)
        );
        assertEquals(
                ConstructionConfirmation26.Phase.CONFIRMED,
                confirmation.snapshot().phase()
        );
    }

    @Test
    void retryBudgetIsConsumedOnlyAfterInteractionWasSent() {
        ConstructionConfirmation26 confirmation =
                new ConstructionConfirmation26(3, 1);
        assertTrue(confirmation.begin(5L, 0L));

        assertEquals(
                ConstructionConfirmation26.Result.RETRY,
                confirmation.observe(5L, false, 3L)
        );
        assertEquals(
                ConstructionConfirmation26.Result.RETRY,
                confirmation.observe(5L, false, 30L)
        );
        assertEquals(0, confirmation.snapshot().retries());

        assertTrue(confirmation.markRetried(30L));
        assertEquals(1, confirmation.snapshot().retries());
        assertEquals(
                ConstructionConfirmation26.Result.FAILED,
                confirmation.observe(5L, false, 33L)
        );
    }

    @Test
    void zeroRetryConfigurationFailsAtFirstDeadline() {
        ConstructionConfirmation26 confirmation =
                new ConstructionConfirmation26(2, 0);
        assertTrue(confirmation.begin(0L, 4L));
        assertEquals(
                ConstructionConfirmation26.Result.FAILED,
                confirmation.observe(0L, false, 6L)
        );
    }

    @Test
    void resetPreventsTransactionStateLeakage() {
        ConstructionConfirmation26 confirmation =
                new ConstructionConfirmation26(2, 1);
        assertTrue(confirmation.begin(4L, 1L));
        confirmation.fail();
        confirmation.reset();

        assertEquals(
                ConstructionConfirmation26.Phase.IDLE,
                confirmation.snapshot().phase()
        );
        assertTrue(confirmation.begin(7L, 9L));
        assertEquals(0, confirmation.snapshot().retries());
    }

    @Test
    void invalidLimitsAndTransactionInputsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConstructionConfirmation26(0, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConstructionConfirmation26(1, -1)
        );
        ConstructionConfirmation26 confirmation =
                new ConstructionConfirmation26(2, 1);
        assertFalse(confirmation.begin(-1L, 0L));
        assertFalse(confirmation.begin(0L, -1L));
    }

    @Test
    void deadlineAdditionSaturatesWithoutOverflow() {
        ConstructionConfirmation26 confirmation =
                new ConstructionConfirmation26(10, 0);
        assertTrue(confirmation.begin(1L, Long.MAX_VALUE - 2L));
        assertEquals(
                Long.MAX_VALUE,
                confirmation.snapshot().deadline()
        );
    }
}
