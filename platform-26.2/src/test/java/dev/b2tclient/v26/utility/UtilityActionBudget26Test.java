package dev.b2tclient.v26.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityActionBudget26Test {
    @Test
    void spacingAndRollingWindowBothLimitActions() {
        UtilityActionBudget26 budget =
                new UtilityActionBudget26(2, 100, 4);

        assertTrue(budget.acquire(10));
        assertFalse(budget.acquire(13));
        assertTrue(budget.acquire(14));
        assertFalse(budget.canAcquire(109));
        assertTrue(budget.canAcquire(110));
    }

    @Test
    void tickRegressionAndResetStartFreshLifecycle() {
        UtilityActionBudget26 budget =
                new UtilityActionBudget26(1, 100, 10);
        assertTrue(budget.acquire(500));
        assertFalse(budget.canAcquire(501));
        assertTrue(budget.acquire(2));
        budget.reset();
        assertTrue(budget.canAcquire(2));
    }

    @Test
    void diagnosticsAndValidationAreBounded() {
        UtilityActionBudget26 budget =
                new UtilityActionBudget26(1, 40, 5);
        assertTrue(budget.acquire(20));
        UtilityActionBudget26.Snapshot snapshot = budget.snapshot(21);
        assertEquals(1, snapshot.actionsInWindow());
        assertEquals(60, snapshot.nextAvailableTick());

        assertThrows(
                IllegalArgumentException.class,
                () -> new UtilityActionBudget26(0, 40, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UtilityActionBudget26(1, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UtilityActionBudget26(1, 40, 41)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> budget.canAcquire(-1)
        );
    }
}
