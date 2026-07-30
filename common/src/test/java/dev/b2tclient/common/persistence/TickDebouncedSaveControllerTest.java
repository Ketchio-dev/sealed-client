package dev.b2tclient.common.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickDebouncedSaveControllerTest {
    @Test
    void repeatedMutationsAreCoalescedAfterAQuietTickWindow() {
        AtomicInteger saves = new AtomicInteger();
        TickDebouncedSaveController controller =
                new TickDebouncedSaveController(3, 10, saves::incrementAndGet);

        controller.markDirty();
        assertEquals(TickDebouncedSaveController.TickResult.PENDING, controller.tick());
        controller.markDirty();
        assertEquals(TickDebouncedSaveController.TickResult.PENDING, controller.tick());
        assertEquals(TickDebouncedSaveController.TickResult.PENDING, controller.tick());
        assertEquals(TickDebouncedSaveController.TickResult.SAVED, controller.tick());

        assertEquals(1, saves.get());
        assertFalse(controller.isDirty());
        assertEquals(TickDebouncedSaveController.TickResult.IDLE, controller.tick());
    }

    @Test
    void continuousInputCannotPostponeSavePastMaximumDelay() {
        AtomicInteger saves = new AtomicInteger();
        TickDebouncedSaveController controller =
                new TickDebouncedSaveController(4, 5, saves::incrementAndGet);

        for (int tick = 0; tick < 4; tick++) {
            controller.markDirty();
            assertEquals(TickDebouncedSaveController.TickResult.PENDING, controller.tick());
        }
        controller.markDirty();
        assertEquals(TickDebouncedSaveController.TickResult.SAVED, controller.tick());

        assertEquals(1, saves.get());
        assertFalse(controller.isDirty());
    }

    @Test
    void failedSaveRemainsDirtyAndRetriesOnlyAfterAnotherDebounceWindow() {
        AtomicInteger attempts = new AtomicInteger();
        TickDebouncedSaveController controller =
                new TickDebouncedSaveController(2, 8, () -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IOException("disk unavailable");
                    }
                });

        controller.markDirty();
        assertEquals(TickDebouncedSaveController.TickResult.PENDING, controller.tick());
        assertEquals(TickDebouncedSaveController.TickResult.FAILED, controller.tick());
        assertTrue(controller.isDirty());
        assertTrue(controller.lastFailure().isPresent());
        assertEquals(TickDebouncedSaveController.TickResult.PENDING, controller.tick());
        assertEquals(TickDebouncedSaveController.TickResult.SAVED, controller.tick());

        assertEquals(2, attempts.get());
        assertFalse(controller.isDirty());
        assertTrue(controller.lastFailure().isEmpty());
    }

    @Test
    void lifecycleFlushWritesDirtyStateImmediatelyAndOnlyOnce() {
        AtomicInteger saves = new AtomicInteger();
        TickDebouncedSaveController controller =
                new TickDebouncedSaveController(20, 200, saves::incrementAndGet);

        controller.markDirty();
        assertEquals(TickDebouncedSaveController.TickResult.SAVED, controller.flush());
        assertEquals(TickDebouncedSaveController.TickResult.IDLE, controller.flush());
        assertEquals(1, saves.get());
    }

    @Test
    void tickWindowsAreStrictlyBounded() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TickDebouncedSaveController(0, 1, () -> {
                })
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TickDebouncedSaveController(10, 9, () -> {
                })
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TickDebouncedSaveController(
                        1,
                        TickDebouncedSaveController.MAX_TICK_WINDOW + 1,
                        () -> {
                        }
                )
        );
    }
}
