package dev.sealedclient.v26.hud;

import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickRateTracker26Test {
    @Test
    void estimatesServerTicksFromAuthoritativeTimeUpdates() {
        AtomicLong nanos = new AtomicLong(1_000_000L);
        TickRateTracker26 tracker = new TickRateTracker26(nanos::get);
        tracker.connect();

        assertFalse(tracker.observeTimeUpdate(1_000L, nanos.get()));
        for (int sample = 1; sample <= 8; sample++) {
            nanos.addAndGet(1_000_000_000L);
            assertTrue(tracker.observeTimeUpdate(
                    1_000L + sample * 20L,
                    nanos.get()
            ));
        }

        TickRateTracker26.Snapshot snapshot = tracker.snapshot();
        assertEquals(TickRateTracker26.Status.LIVE, snapshot.status());
        assertEquals(20.0, snapshot.ticksPerSecond(), 0.001);
        assertEquals(8, snapshot.sampleCount());
        assertEquals("Server TPS 20.0", snapshot.displayText());
    }

    @Test
    void packetEntryIgnoresNoiseAndAcceptsSetTimePackets() {
        AtomicLong nanos = new AtomicLong(100L);
        TickRateTracker26 tracker = new TickRateTracker26(nanos::get);
        tracker.connect();

        assertFalse(tracker.observeInbound(
                new ClientboundSetTimePacket(10L, Map.of())
        ));
        nanos.addAndGet(1_000_000_000L);
        assertTrue(tracker.observeInbound(
                new ClientboundSetTimePacket(30L, Map.of())
        ));
        assertEquals(20.0, tracker.snapshot().ticksPerSecond(), 0.001);
    }

    @Test
    void robustWindowRejectsIsolatedLagAndRemainsBounded() {
        AtomicLong nanos = new AtomicLong(1L);
        TickRateTracker26 tracker = new TickRateTracker26(nanos::get);
        tracker.connect();
        long gameTime = 0L;
        tracker.observeTimeUpdate(gameTime, nanos.get());

        for (int sample = 0; sample < 9; sample++) {
            gameTime += 20L;
            nanos.addAndGet(1_000_000_000L);
            tracker.observeTimeUpdate(gameTime, nanos.get());
        }
        gameTime += 2L;
        nanos.addAndGet(1_000_000_000L);
        tracker.observeTimeUpdate(gameTime, nanos.get());

        TickRateTracker26.Snapshot robust = tracker.snapshot();
        assertEquals(20.0, robust.ticksPerSecond(), 0.001);
        assertTrue(robust.ticksPerSecond() <= TickRateTracker26.MAX_TPS);

        for (int sample = 0; sample < TickRateTracker26.WINDOW_SIZE + 2;
             sample++) {
            gameTime += 10L;
            nanos.addAndGet(1_000_000_000L);
            tracker.observeTimeUpdate(gameTime, nanos.get());
        }
        TickRateTracker26.Snapshot adapted = tracker.snapshot();
        assertEquals(TickRateTracker26.WINDOW_SIZE, adapted.sampleCount());
        assertEquals(10.0, adapted.ticksPerSecond(), 0.001);
    }

    @Test
    void staleDisconnectAndTimeRegressionHaveExplicitStates() {
        TickRateTracker26 tracker = new TickRateTracker26(() -> 0L);
        assertEquals(
                TickRateTracker26.Status.DISCONNECTED,
                tracker.snapshot(0L).status()
        );

        tracker.connect(1L);
        tracker.observeTimeUpdate(100L, 1L);
        assertEquals(
                TickRateTracker26.Status.MEASURING,
                tracker.snapshot(1L).status()
        );
        assertEquals(
                TickRateTracker26.Status.STALE,
                tracker.snapshot(
                        1L + TickRateTracker26.STALE_AFTER_NANOS
                ).status()
        );

        tracker.observeTimeUpdate(120L, 1_000_000_001L);
        assertEquals(TickRateTracker26.Status.LIVE,
                tracker.snapshot(1_000_000_001L).status());
        assertFalse(tracker.observeTimeUpdate(120L, 1_500_000_001L));
        assertEquals(1, tracker.snapshot(1_500_000_001L).sampleCount());
        assertEquals(
                20.0,
                tracker.snapshot(1_500_000_001L).ticksPerSecond(),
                0.001
        );
        tracker.observeTimeUpdate(5L, 2_000_000_001L);
        assertEquals(
                TickRateTracker26.Status.MEASURING,
                tracker.snapshot(2_000_000_001L).status()
        );

        tracker.disconnect();
        TickRateTracker26.Snapshot disconnected =
                tracker.snapshot(3_000_000_001L);
        assertEquals(
                TickRateTracker26.Status.DISCONNECTED,
                disconnected.status()
        );
        assertEquals(0, disconnected.sampleCount());
        assertEquals(-1L, disconnected.sampleAgeMillis());
    }

    @Test
    void concurrentPublishingAndSnapshotsStayConsistent() throws Exception {
        TickRateTracker26 tracker = new TickRateTracker26(() -> 0L);
        tracker.connect(1L);
        tracker.observeTimeUpdate(0L, 1L);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> {
                try {
                    start.await();
                    long now = 1L;
                    for (int sample = 1; sample <= 2_000; sample++) {
                        now += 1_000_000_000L;
                        tracker.observeTimeUpdate(sample * 20L, now);
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
            executor.submit(() -> {
                try {
                    start.await();
                    for (int sample = 0; sample < 2_000; sample++) {
                        TickRateTracker26.Snapshot snapshot =
                                tracker.snapshot(Long.MAX_VALUE);
                        assertTrue(snapshot.sampleCount() >= 0);
                        assertTrue(snapshot.sampleCount()
                                <= TickRateTracker26.WINDOW_SIZE);
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        }

        assertNull(failure.get());
        assertDoesNotThrow(() -> tracker.snapshot(Long.MAX_VALUE));
    }
}
