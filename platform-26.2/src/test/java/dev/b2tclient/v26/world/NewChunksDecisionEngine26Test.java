package dev.b2tclient.v26.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewChunksDecisionEngine26Test {
    private static final NewChunksDecisionEngine26.SessionKey OVERWORLD =
            new NewChunksDecisionEngine26.SessionKey(1L, "minecraft:overworld");

    @Test
    void baselineNeedsCompleteSweepsBeforeFreshClassification() {
        NewChunksDecisionEngine26 engine = new NewChunksDecisionEngine26();
        NewChunksDecisionEngine26.Config config = config(1_000L, 16);
        Set<Long> loaded = new HashSet<>();
        loaded.add(pack(0, 0));

        assertBudget(engine.tick(
                OVERWORLD, 0L, 0, 0, config, lookup(loaded)
        ), config);
        assertFalse(engine.status().baselineEstablished());

        assertBudget(engine.tick(
                OVERWORLD, 1L, 0, 0, config, lookup(loaded)
        ), config);
        assertFalse(engine.status().baselineEstablished());

        assertBudget(engine.tick(
                OVERWORLD, 2L, 0, 0, config, lookup(loaded)
        ), config);
        assertTrue(engine.status().baselineEstablished());
        assertTrue(engine.freshlyObservedSnapshot().isEmpty());

        loaded.add(pack(1, 0));
        NewChunksDecisionEngine26.TickResult fresh = engine.tick(
                OVERWORLD, 3L, 1, 0, config, lookup(loaded)
        );

        assertBudget(fresh, config);
        assertEquals(1, fresh.newlyClassified());
        assertEquals(
                NewChunksDecisionEngine26.Classification.FIRST_SEEN,
                engine.freshlyObservedSnapshot().getFirst().classification()
        );
    }

    @Test
    void observesLoadedCoordinatesOnlyAndTreatsLookupFailureAsUnknown() {
        NewChunksDecisionEngine26 engine = new NewChunksDecisionEngine26();
        NewChunksDecisionEngine26.Config config =
                new NewChunksDecisionEngine26.Config(
                        1, 9, 100L, 32, 1, 0L
                );

        NewChunksDecisionEngine26.TickResult first = engine.tick(
                OVERWORLD,
                0L,
                0,
                0,
                config,
                (x, z) -> {
                    if (x == -1 && z == -1) {
                        throw new IllegalStateException("replaced");
                    }
                    return x == 0 && z == 0;
                }
        );

        assertEquals(9, first.loadedChunkLookups());
        assertEquals(1, first.loadedChunksObserved());
        assertEquals(1, engine.snapshot().size());
        assertEquals(0, engine.snapshot().getFirst().chunkX());
        assertEquals(0, engine.snapshot().getFirst().chunkZ());
    }

    @Test
    void expiryAndBloomFilterCanSuppressButNeverInventFreshChunks() {
        NewChunksDecisionEngine26 engine = new NewChunksDecisionEngine26();
        NewChunksDecisionEngine26.Config config = config(4L, 16);
        Set<Long> loaded = new HashSet<>();
        loaded.add(pack(0, 0));

        engine.tick(OVERWORLD, 0L, 0, 0, config, lookup(loaded));
        engine.tick(OVERWORLD, 1L, 0, 0, config, lookup(loaded));
        engine.tick(OVERWORLD, 2L, 0, 0, config, lookup(loaded));
        loaded.add(pack(1, 0));
        engine.tick(OVERWORLD, 3L, 1, 0, config, lookup(loaded));
        assertEquals(1, engine.freshlyObservedSnapshot().size());

        engine.tick(OVERWORLD, 6L, 1, 0, config, lookup(loaded));
        engine.tick(OVERWORLD, 7L, 1, 0, config, lookup(loaded));
        engine.tick(OVERWORLD, 8L, 1, 0, config, lookup(loaded));
        assertTrue(engine.snapshot().isEmpty());

        engine.tick(OVERWORLD, 9L, 1, 0, config, lookup(loaded));
        assertTrue(engine.freshlyObservedSnapshot().isEmpty());
        assertEquals(
                NewChunksDecisionEngine26.Classification.BASELINE,
                engine.snapshot().getFirst().classification()
        );
    }

    @Test
    void cacheIsBoundedSnapshotsAreImmutableAndSessionChangeResets() {
        NewChunksDecisionEngine26 engine = new NewChunksDecisionEngine26();
        NewChunksDecisionEngine26.Config config =
                new NewChunksDecisionEngine26.Config(
                        0, 8, 1_000L, 2, 1, 1L
                );

        for (int tick = 0; tick < 8; tick++) {
            int center = Math.max(0, tick - 1);
            NewChunksDecisionEngine26.TickResult result = engine.tick(
                    OVERWORLD,
                    tick,
                    center,
                    0,
                    config,
                    (x, z) -> true
            );
            assertBudget(result, config);
            assertTrue(engine.snapshot().size() <= 2);
        }

        List<NewChunksDecisionEngine26.ChunkSnapshot> snapshot =
                engine.snapshot();
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.add(snapshot.getFirst())
        );

        NewChunksDecisionEngine26.SessionKey nether =
                new NewChunksDecisionEngine26.SessionKey(
                        1L,
                        "minecraft:the_nether"
                );
        NewChunksDecisionEngine26.TickResult reset = engine.tick(
                nether,
                8L,
                0,
                0,
                config,
                (x, z) -> false
        );
        assertTrue(reset.sessionReset());
        assertTrue(engine.snapshot().isEmpty());
        assertFalse(engine.status().baselineEstablished());
    }

    @Test
    void validatesConfigurationAndSessionInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NewChunksDecisionEngine26.Config(
                        33, 1, 1L, 1, 1, 0L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new NewChunksDecisionEngine26.SessionKey(-1L, "world")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new NewChunksDecisionEngine26.SessionKey(1L, " ")
        );
    }

    private static NewChunksDecisionEngine26.Config config(
            long lifetime,
            int maximumEntries
    ) {
        return new NewChunksDecisionEngine26.Config(
                0,
                1,
                lifetime,
                maximumEntries,
                2,
                0L
        );
    }

    private static NewChunksDecisionEngine26.LoadedChunkLookup lookup(
            Set<Long> loaded
    ) {
        return (x, z) -> loaded.contains(pack(x, z));
    }

    private static long pack(int x, int z) {
        return ((long) x & 0xFFFF_FFFFL)
                | (((long) z & 0xFFFF_FFFFL) << 32);
    }

    private static void assertBudget(
            NewChunksDecisionEngine26.TickResult result,
            NewChunksDecisionEngine26.Config config
    ) {
        assertTrue(result.operations() <= config.operationBudget());
    }
}
