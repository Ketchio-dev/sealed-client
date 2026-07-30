package dev.b2tclient.v26.hud;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTotemPopTracker26Test {
    private static final UUID LOCAL =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final int ENTITY_ID = 17;

    @Test
    void inventoryAndHealthTransitionsNeverCreateFalsePops() {
        AtomicLong nanos = new AtomicLong(1L);
        LocalTotemPopTracker26 tracker =
                new LocalTotemPopTracker26(nanos::get);
        tracker.connect(LOCAL, ENTITY_ID);

        assertTrue(tracker.observeState(
                LOCAL, ENTITY_ID, 20.0F, 1, true
        ));
        assertTrue(tracker.observeState(
                LOCAL, ENTITY_ID, 12.0F, 0, true
        ));
        assertEquals(0, tracker.snapshot().popCount());

        assertEquals(
                LocalTotemPopTracker26.EventResult.ACCEPTED,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID, 12.0F, 0, true
                )
        );
        assertEquals(1, tracker.snapshot().popCount());
    }

    @Test
    void everyAuthoritativeEventCountsEvenWhenPacketsArriveTogether() {
        LocalTotemPopTracker26 tracker =
                new LocalTotemPopTracker26(() -> 0L);
        tracker.connect(LOCAL, ENTITY_ID, 0L);

        assertEquals(
                LocalTotemPopTracker26.EventResult.ACCEPTED,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID, 1.0F, 1, true, 1_000_000L
                )
        );
        assertEquals(
                LocalTotemPopTracker26.EventResult.ACCEPTED,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID, 1.0F, 1, true, 2_000_000L
                )
        );
        assertEquals(
                LocalTotemPopTracker26.EventResult.ACCEPTED,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID, 1.0F, 1, true, 3_000_000L
                )
        );
        assertEquals(3, tracker.snapshot(3_000_000L).popCount());
    }

    @Test
    void rejectsForeignEntityAndInvalidLocalState() {
        LocalTotemPopTracker26 tracker =
                new LocalTotemPopTracker26(() -> 0L);
        tracker.connect(LOCAL, ENTITY_ID, 0L);

        assertEquals(
                LocalTotemPopTracker26.EventResult.WRONG_PLAYER,
                tracker.observeProtectedFromDeath(
                        OTHER, ENTITY_ID, 20.0F, 1, true, 1L
                )
        );
        assertEquals(
                LocalTotemPopTracker26.EventResult.WRONG_PLAYER,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID + 1, 20.0F, 1, true, 2L
                )
        );
        assertEquals(
                LocalTotemPopTracker26.EventResult.INVALID_STATE,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID, Float.NaN, 1, true, 3L
                )
        );
        assertEquals(
                LocalTotemPopTracker26.EventResult.INVALID_STATE,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID, -1.0F, 1, false, 4L
                )
        );
        assertEquals(0, tracker.snapshot(4L).popCount());
    }

    @Test
    void deathDisconnectAndNewEntityResetTheSessionCounter() {
        LocalTotemPopTracker26 tracker =
                new LocalTotemPopTracker26(() -> 0L);
        tracker.connect(LOCAL, ENTITY_ID, 0L);
        tracker.observeProtectedFromDeath(
                LOCAL, ENTITY_ID, 1.0F, 0, true, 20_000_000L
        );
        tracker.observeState(
                LOCAL, ENTITY_ID, 0.0F, 0, false, 30_000_000L
        );

        LocalTotemPopTracker26.Snapshot dead =
                tracker.snapshot(30_000_000L);
        assertEquals(LocalTotemPopTracker26.Status.DEAD, dead.status());
        assertEquals(0, dead.popCount());

        tracker.connect(LOCAL, ENTITY_ID + 1, 40_000_000L);
        assertEquals(0, tracker.snapshot(40_000_000L).popCount());
        tracker.disconnect();
        assertEquals(
                LocalTotemPopTracker26.Status.DISCONNECTED,
                tracker.snapshot(50_000_000L).status()
        );
    }

    @Test
    void authoritativeEventSurvivesHealthPacketReordering() {
        LocalTotemPopTracker26 tracker =
                new LocalTotemPopTracker26(() -> 0L);
        tracker.connect(LOCAL, ENTITY_ID, 0L);

        assertEquals(
                LocalTotemPopTracker26.EventResult.ACCEPTED,
                tracker.observeProtectedFromDeath(
                        LOCAL, ENTITY_ID, 0.0F, 1, false, 20_000_000L
                )
        );
        assertEquals(
                LocalTotemPopTracker26.Status.TRACKING,
                tracker.snapshot(20_000_000L).status()
        );
        tracker.observeState(
                LOCAL, ENTITY_ID, 1.0F, 0, true, 30_000_000L
        );
        assertEquals(1, tracker.snapshot(30_000_000L).popCount());

        tracker.observeState(
                LOCAL, ENTITY_ID, 0.0F, 0, false, 40_000_000L
        );
        assertEquals(0, tracker.snapshot(40_000_000L).popCount());
        assertEquals(
                LocalTotemPopTracker26.Status.DEAD,
                tracker.snapshot(40_000_000L).status()
        );
    }

    @Test
    void countAndRecentDisplayAreBounded() {
        LocalTotemPopTracker26 tracker =
                new LocalTotemPopTracker26(() -> 0L);
        tracker.connect(LOCAL, ENTITY_ID, 0L);
        long now = 0L;
        for (int pop = 0;
             pop <= LocalTotemPopTracker26.MAX_POP_COUNT;
             pop++) {
            now += 20_000_000L;
            assertEquals(
                    LocalTotemPopTracker26.EventResult.ACCEPTED,
                    tracker.observeProtectedFromDeath(
                            LOCAL, ENTITY_ID, 1.0F, 1, true, now
                    )
            );
        }

        LocalTotemPopTracker26.Snapshot capped = tracker.snapshot(now);
        assertEquals(LocalTotemPopTracker26.MAX_POP_COUNT, capped.popCount());
        assertTrue(capped.saturated());
        assertTrue(capped.recentPop());
        assertEquals(
                "Local pops " + LocalTotemPopTracker26.MAX_POP_COUNT + "+",
                capped.displayText()
        );

        LocalTotemPopTracker26.Snapshot expired = tracker.snapshot(
                now + LocalTotemPopTracker26.RECENT_DISPLAY_NANOS + 1L
        );
        assertFalse(expired.recentPop());
        assertEquals(capped.popCount(), expired.popCount());
    }
}
