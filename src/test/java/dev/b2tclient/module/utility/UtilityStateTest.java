package dev.b2tclient.module.utility;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityStateTest {
    @Test
    void replenishSelectsTheLargestMatchingStackAndKeepsTheFirstTie() {
        int[] counts = {0, 12, 5, 12, 64};
        Set<Integer> matches = Set.of(1, 2, 3);

        assertEquals(
                1,
                ReplenishModule.largestMatchingSlot(
                        0,
                        counts.length,
                        matches::contains,
                        slot -> counts[slot]
                )
        );
        assertEquals(
                -1,
                ReplenishModule.largestMatchingSlot(
                        0,
                        counts.length,
                        slot -> false,
                        slot -> counts[slot]
                )
        );
    }

    @Test
    void reconnectScheduleWaitsCapsAttemptsAndResetsAfterAConnection() {
        AutoReconnectModule.ReconnectSchedule schedule =
                new AutoReconnectModule.ReconnectSchedule();
        Object firstDisconnect = new Object();

        for (int tick = 0; tick < 40; tick++) {
            assertFalse(schedule.tick(firstDisconnect, 40, 2));
        }
        assertTrue(schedule.tick(firstDisconnect, 40, 2));
        assertEquals(1, schedule.attempts());

        Object secondDisconnect = new Object();
        for (int tick = 0; tick < 40; tick++) {
            assertFalse(schedule.tick(secondDisconnect, 40, 2));
        }
        assertTrue(schedule.tick(secondDisconnect, 40, 2));
        assertEquals(2, schedule.attempts());
        assertFalse(schedule.tick(new Object(), 0, 2));

        schedule.connected();
        assertEquals(0, schedule.attempts());
        assertEquals(-1, schedule.remainingTicks());
        assertTrue(schedule.tick(new Object(), 0, 2));
    }

    @Test
    void reconnectScheduleDoesNotSpendAnAttemptWhenTheNetworkClaimIsBusy() {
        AutoReconnectModule.ReconnectSchedule schedule =
                new AutoReconnectModule.ReconnectSchedule();

        assertTrue(schedule.tick(new Object(), 0, 3));
        schedule.retryAfter(5);

        assertEquals(0, schedule.attempts());
        assertEquals(5, schedule.remainingTicks());
    }
}
