package dev.sealedclient.v26.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPacketBudget26Test {
    @Test
    void minimumSpacingBlocksPerTickPacketSpam() {
        MovementPacketBudget26 budget =
                new MovementPacketBudget26(2, 200, 20);

        assertTrue(budget.acquire(10));
        assertFalse(budget.canAcquire(10));
        assertFalse(budget.acquire(29));
        assertTrue(budget.acquire(30));
        assertFalse(budget.canAcquire(50));
    }

    @Test
    void rollingWindowRestoresCapacityAtExactBoundary() {
        MovementPacketBudget26 budget =
                new MovementPacketBudget26(2, 100, 10);

        assertTrue(budget.acquire(5));
        assertTrue(budget.acquire(15));
        assertFalse(budget.canAcquire(104));
        assertTrue(budget.canAcquire(105));
        assertTrue(budget.acquire(105));
        assertFalse(budget.canAcquire(114));
        assertTrue(budget.canAcquire(115));
    }

    @Test
    void tickRegressionStartsAFreshSession() {
        MovementPacketBudget26 budget =
                new MovementPacketBudget26(1, 100, 20);

        assertTrue(budget.acquire(500));
        assertFalse(budget.canAcquire(510));
        assertTrue(budget.canAcquire(2));
        assertTrue(budget.acquire(2));
    }

    @Test
    void snapshotIsBoundedAndIdentifiesNextPermit() {
        MovementPacketBudget26 budget =
                new MovementPacketBudget26(2, 200, 20);
        assertTrue(budget.acquire(10));

        MovementPacketBudget26.Snapshot one = budget.snapshot(11);
        assertEquals(1, one.actionsInWindow());
        assertEquals(2, one.maximumActions());
        assertEquals(30, one.nextAvailableTick());

        assertTrue(budget.acquire(30));
        MovementPacketBudget26.Snapshot full = budget.snapshot(31);
        assertEquals(2, full.actionsInWindow());
        assertEquals(210, full.nextAvailableTick());
    }

    @Test
    void fullWindowDiagnosticAlsoHonorsLongerSpacing() {
        MovementPacketBudget26 budget =
                new MovementPacketBudget26(2, 100, 90);
        assertTrue(budget.acquire(0));
        assertTrue(budget.acquire(90));

        MovementPacketBudget26.Snapshot snapshot = budget.snapshot(91);

        assertEquals(180, snapshot.nextSpacingTick());
        assertEquals(180, snapshot.nextAvailableTick());
        assertFalse(budget.canAcquire(100));
        assertTrue(budget.canAcquire(180));
    }

    @Test
    void resetAndConfigurationValidationFailSafe() {
        MovementPacketBudget26 budget =
                new MovementPacketBudget26(1, 40, 10);
        assertTrue(budget.acquire(20));
        budget.reset();
        assertTrue(budget.canAcquire(20));

        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementPacketBudget26(0, 40, 10)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementPacketBudget26(1, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementPacketBudget26(1, 40, 41)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> budget.canAcquire(-1)
        );
    }
}
