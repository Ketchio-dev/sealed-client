package dev.b2tclient.v26.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.b2tclient.v26.world.StashFinderDecisionEngine26.ContainerKind.BARREL;
import static dev.b2tclient.v26.world.StashFinderDecisionEngine26.ContainerKind.CHEST;
import static dev.b2tclient.v26.world.StashFinderDecisionEngine26.ContainerKind.IGNORED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashFinderDecisionEngine26Test {
    private static final StashFinderDecisionEngine26.SessionKey OVERWORLD =
            new StashFinderDecisionEngine26.SessionKey(
                    3L,
                    "minecraft:overworld"
            );

    @Test
    void scansLoadedChunksOnlyDeduplicatesAndPublishesCompleteSweep() {
        StashFinderDecisionEngine26 engine =
                new StashFinderDecisionEngine26();
        StashFinderDecisionEngine26.Config config = config(
                16, 3, 2, 8, 100, 1_000L
        );
        MutableChunkSource source = new MutableChunkSource();
        source.put(
                0,
                0,
                observation(0, 64, 0, CHEST),
                observation(0, 64, 0, BARREL),
                observation(1, 64, 0, BARREL),
                observation(2, 64, 0, IGNORED),
                observation(15, 64, 15, CHEST),
                observation(32, 64, 0, CHEST)
        );

        long nextTick = driveUntilPublished(
                engine, OVERWORLD, 0L, 0, 64, 0, config, source
        );
        assertTrue(nextTick > 0L);
        assertTrue(source.loadedLookups > source.openedLoadedChunks);
        assertEquals(source.openedLoadedChunks, source.entityOpenCalls);

        List<StashFinderDecisionEngine26.StashSnapshot> snapshot =
                engine.snapshot();
        assertEquals(1, snapshot.size());
        StashFinderDecisionEngine26.StashSnapshot stash =
                snapshot.getFirst();
        assertEquals(2, stash.containerCount());
        assertEquals(1, stash.containerCounts().get(CHEST));
        assertEquals(1, stash.containerCounts().get(BARREL));
        assertEquals(
                StashFinderDecisionEngine26.Evidence.BASELINE,
                stash.evidence()
        );
        assertFalse(stash.containerCounts().containsKey(IGNORED));
    }

    @Test
    void allPhasesShareTheHardPerTickBudget() {
        StashFinderDecisionEngine26 engine =
                new StashFinderDecisionEngine26();
        StashFinderDecisionEngine26.Config config = config(
                64, 2, 2, 16, 128, 1_000L
        );
        MutableChunkSource source = new MutableChunkSource();
        for (int chunk = -4; chunk <= 4; chunk += 2) {
            source.put(
                    chunk,
                    chunk,
                    observation(chunk * 16, 64, chunk * 16, CHEST),
                    observation(chunk * 16 + 1, 64, chunk * 16, BARREL)
            );
        }

        boolean sawScanning = false;
        boolean sawClustering = false;
        boolean sawMerging = false;
        boolean sawSnapshotting = false;
        for (long tick = 0L; tick < 1_000L; tick++) {
            StashFinderDecisionEngine26.TickResult result = engine.tick(
                    OVERWORLD, tick, 0, 64, 0, config, source
            );
            assertTrue(result.operations() <= config.operationBudget());
            sawScanning |= result.phase()
                    == StashFinderDecisionEngine26.Phase.SCANNING;
            sawClustering |= result.phase()
                    == StashFinderDecisionEngine26.Phase.CLUSTERING;
            sawMerging |= result.phase()
                    == StashFinderDecisionEngine26.Phase.MERGING;
            sawSnapshotting |= result.phase()
                    == StashFinderDecisionEngine26.Phase.SNAPSHOTTING;
            if (result.publishedSweeps() > 0) {
                break;
            }
        }

        assertTrue(sawScanning);
        assertTrue(sawClustering);
        assertTrue(sawMerging);
        assertTrue(sawSnapshotting);
        assertFalse(engine.snapshot().isEmpty());
    }

    @Test
    void baselineThenFirstSeenClustersRemainDeduplicatedAcrossSweeps() {
        StashFinderDecisionEngine26 engine =
                new StashFinderDecisionEngine26();
        StashFinderDecisionEngine26.Config config = config(
                64, 4, 2, 8, 100, 1_000L
        );
        MutableChunkSource source = new MutableChunkSource();
        source.put(
                0,
                0,
                observation(0, 64, 0, CHEST),
                observation(1, 64, 0, CHEST)
        );

        long tick = driveUntilPublished(
                engine, OVERWORLD, 0L, 0, 64, 0, config, source
        );
        assertEquals(1, engine.snapshot().size());
        long baselineId = engine.snapshot().getFirst().id();

        source.clearEntities();
        source.put(
                3,
                0,
                observation(48, 70, 0, BARREL),
                observation(49, 70, 0, BARREL),
                observation(50, 70, 0, BARREL)
        );
        tick = driveUntilPublished(
                engine, OVERWORLD, tick, 0, 64, 0, config, source
        );

        assertEquals(2, engine.snapshot().size());
        assertEquals(baselineId, engine.snapshot().getFirst().id());
        StashFinderDecisionEngine26.StashSnapshot fresh =
                engine.snapshot().get(1);
        assertEquals(
                StashFinderDecisionEngine26.Evidence.FIRST_SEEN,
                fresh.evidence()
        );
        long freshId = fresh.id();

        tick = driveUntilPublished(
                engine, OVERWORLD, tick, 0, 64, 0, config, source
        );
        assertTrue(tick > 0L);
        assertEquals(2, engine.snapshot().size());
        assertEquals(freshId, engine.snapshot().get(1).id());
        assertEquals(3, engine.snapshot().get(1).containerCount());
    }

    @Test
    void observationAndResultCachesHaveIndependentHardBounds() {
        StashFinderDecisionEngine26 engine =
                new StashFinderDecisionEngine26();
        StashFinderDecisionEngine26.Config config = config(
                64, 4, 2, 1, 2, 1_000L
        );
        MutableChunkSource source = new MutableChunkSource();
        source.put(
                0,
                0,
                observation(0, 64, 0, CHEST),
                observation(1, 64, 0, CHEST),
                observation(2, 64, 0, CHEST)
        );
        source.put(
                3,
                0,
                observation(48, 64, 0, BARREL),
                observation(49, 64, 0, BARREL)
        );

        driveUntilPublished(
                engine, OVERWORLD, 0L, 0, 64, 0, config, source
        );

        assertTrue(engine.snapshot().size() <= 1);
        assertTrue(
                engine.scanProgress().lastPublishedObservationLimitReached()
        );
        assertTrue(
                engine.snapshot().stream()
                        .allMatch(stash -> stash.containerCount() <= 2)
        );
    }

    @Test
    void staleResultsExpireAndDimensionResetClearsPublishedSnapshot() {
        StashFinderDecisionEngine26 engine =
                new StashFinderDecisionEngine26();
        StashFinderDecisionEngine26.Config config = config(
                16, 4, 2, 8, 32, 5L
        );
        MutableChunkSource source = new MutableChunkSource();
        source.put(
                0,
                0,
                observation(0, 64, 0, CHEST),
                observation(1, 64, 0, CHEST)
        );
        long tick = driveUntilPublished(
                engine, OVERWORLD, 0L, 0, 64, 0, config, source
        );
        assertEquals(1, engine.snapshot().size());

        source.clearEntities();
        tick = Math.max(tick, 100L);
        tick = driveUntilPublished(
                engine, OVERWORLD, tick, 0, 64, 0, config, source
        );
        assertTrue(engine.snapshot().isEmpty());

        source.put(
                0,
                0,
                observation(0, 64, 0, CHEST),
                observation(1, 64, 0, CHEST)
        );
        tick = driveUntilPublished(
                engine, OVERWORLD, tick, 0, 64, 0, config, source
        );
        assertFalse(engine.snapshot().isEmpty());

        StashFinderDecisionEngine26.SessionKey nether =
                new StashFinderDecisionEngine26.SessionKey(
                        3L,
                        "minecraft:the_nether"
                );
        StashFinderDecisionEngine26.TickResult reset = engine.tick(
                nether,
                tick,
                0,
                64,
                0,
                config,
                source
        );
        assertTrue(reset.sessionReset());
        assertTrue(engine.snapshot().isEmpty());
    }

    @Test
    void snapshotsAndNestedCountMapsAreImmutable() {
        StashFinderDecisionEngine26 engine =
                new StashFinderDecisionEngine26();
        StashFinderDecisionEngine26.Config config = config(
                16, 4, 2, 8, 32, 1_000L
        );
        MutableChunkSource source = new MutableChunkSource();
        source.put(
                0,
                0,
                observation(0, 64, 0, CHEST),
                observation(1, 64, 0, CHEST)
        );
        driveUntilPublished(
                engine, OVERWORLD, 0L, 0, 64, 0, config, source
        );

        List<StashFinderDecisionEngine26.StashSnapshot> snapshot =
                engine.snapshot();
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.add(snapshot.getFirst())
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.getFirst().containerCounts().put(BARREL, 99)
        );
    }

    @Test
    void validatesConfigurationAndSessionIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> config(15, 1, 2, 1, 2, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> config(16, 1, 4, 1, 3, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new StashFinderDecisionEngine26.SessionKey(0L, " ")
        );
    }

    private static long driveUntilPublished(
            StashFinderDecisionEngine26 engine,
            StashFinderDecisionEngine26.SessionKey session,
            long firstTick,
            int originX,
            int originY,
            int originZ,
            StashFinderDecisionEngine26.Config config,
            MutableChunkSource source
    ) {
        for (long tick = firstTick; tick < firstTick + 2_000L; tick++) {
            StashFinderDecisionEngine26.TickResult result = engine.tick(
                    session,
                    tick,
                    originX,
                    originY,
                    originZ,
                    config,
                    source
            );
            assertTrue(result.operations() <= config.operationBudget());
            if (result.publishedSweeps() > 0) {
                return tick + 1L;
            }
        }
        throw new AssertionError("sweep was not published");
    }

    private static StashFinderDecisionEngine26.Config config(
            int range,
            int budget,
            int minimum,
            int maximumEntries,
            int maximumObservations,
            long lifetime
    ) {
        return new StashFinderDecisionEngine26.Config(
                range,
                budget,
                minimum,
                maximumEntries,
                maximumObservations,
                lifetime
        );
    }

    private static StashFinderDecisionEngine26.BlockObservation observation(
            int x,
            int y,
            int z,
            StashFinderDecisionEngine26.ContainerKind kind
    ) {
        return new StashFinderDecisionEngine26.BlockObservation(x, y, z, kind);
    }

    private static long pack(int x, int z) {
        return ((long) x & 0xFFFF_FFFFL)
                | (((long) z & 0xFFFF_FFFFL) << 32);
    }

    private static final class MutableChunkSource
            implements StashFinderDecisionEngine26.LoadedChunkSource {
        private final Set<Long> loaded = new HashSet<>();
        private final Map<Long, List<StashFinderDecisionEngine26.BlockObservation>>
                entities = new HashMap<>();
        private int loadedLookups;
        private int openedLoadedChunks;
        private int entityOpenCalls;

        private void put(
                int chunkX,
                int chunkZ,
                StashFinderDecisionEngine26.BlockObservation... observations
        ) {
            long key = pack(chunkX, chunkZ);
            loaded.add(key);
            entities.put(key, new ArrayList<>(List.of(observations)));
        }

        private void clearEntities() {
            entities.clear();
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            loadedLookups++;
            boolean result = loaded.contains(pack(chunkX, chunkZ));
            if (result) {
                openedLoadedChunks++;
            }
            return result;
        }

        @Override
        public Iterable<StashFinderDecisionEngine26.BlockObservation>
                blockEntitiesInLoadedChunk(int chunkX, int chunkZ) {
            entityOpenCalls++;
            long key = pack(chunkX, chunkZ);
            if (!loaded.contains(key)) {
                throw new AssertionError("opened an unloaded chunk");
            }
            return entities.getOrDefault(key, List.of());
        }
    }
}
