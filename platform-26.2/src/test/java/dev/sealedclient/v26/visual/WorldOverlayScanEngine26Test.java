package dev.sealedclient.v26.visual;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldOverlayScanEngine26Test {
    private final Object session = new Object();
    private final Object world = new Object();
    private final WorldOverlayScanEngine26.Scope scope =
            new WorldOverlayScanEngine26.Scope(
                    session,
                    world,
                    "overworld"
            );

    @Test
    void rejectsBudgetsAboveAbsoluteEngineCeilings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldOverlayScanEngine26.Configuration(
                        WorldOverlayScanEngine26.HARD_MAX_SCAN_BUDGET + 1,
                        1,
                        WorldOverlayScanEngine26.HARD_MAX_PENDING_CANDIDATES,
                        1,
                        64.0,
                        20L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldOverlayScanEngine26.Configuration(
                        1,
                        1,
                        1,
                        WorldOverlayScanEngine26.HARD_MAX_CACHE_ENTRIES + 1,
                        64.0,
                        20L
                )
        );
    }
    private final WorldOverlayScanEngine26.Point origin =
            new WorldOverlayScanEngine26.Point(0.0, 0.0, 0.0);

    @Test
    void scanAndAdmissionBudgetsAreExactAndQueueWorkAcrossTicks() {
        WorldOverlayScanEngine26<Integer, String> engine = engine(
                2,
                3,
                8,
                8,
                100.0,
                100L
        );
        AtomicInteger iteratorReads = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        Iterable<WorldOverlayScanEngine26.Candidate<Integer>> lazy =
                boundedCountingCandidates(iteratorReads, 100);

        WorldOverlayScanEngine26.TickResult<Integer, String> first =
                engine.tick(scope, origin, lazy, candidate -> {
                    probes.incrementAndGet();
                    return WorldOverlayScanEngine26.ProbeResult.hit(
                            "v" + candidate.key()
                    );
                });

        assertTrue(first.scopeReset());
        assertEquals(3, iteratorReads.get());
        assertEquals(3, first.candidatesExamined());
        assertEquals(2, first.scansPerformed());
        assertEquals(0, first.remainingScanBudget());
        assertEquals(1, first.pendingCandidates());
        assertEquals(2, first.cacheSize());
        assertEquals(2, probes.get());

        WorldOverlayScanEngine26.TickResult<Integer, String> second =
                engine.tick(scope, origin, List.of(), candidate -> {
                    probes.incrementAndGet();
                    return WorldOverlayScanEngine26.ProbeResult.hit(
                            "v" + candidate.key()
                    );
                });

        assertFalse(second.scopeReset());
        assertEquals(1, second.scansPerformed());
        assertEquals(1, second.remainingScanBudget());
        assertEquals(0, second.pendingCandidates());
        assertEquals(3, second.cacheSize());
        assertEquals(3, probes.get());
    }

    @Test
    void pendingAndCacheCapsAreHardAndPreferNearerData() {
        WorldOverlayScanEngine26<Integer, String> pendingEngine = engine(
                1,
                10,
                3,
                8,
                1_000.0,
                100L
        );
        List<WorldOverlayScanEngine26.Candidate<Integer>> candidates =
                List.of(
                        candidate(100, 100.0),
                        candidate(90, 90.0),
                        candidate(80, 80.0),
                        candidate(1, 1.0),
                        candidate(70, 70.0)
                );

        WorldOverlayScanEngine26.TickResult<Integer, String> first =
                pendingEngine.tick(
                        scope,
                        origin,
                        candidates,
                        hitWithKey()
                );

        assertEquals(2, first.pendingCapacityEvictions());
        assertTrue(first.pendingCandidates() <= 3);
        assertEquals(1, first.scansPerformed());

        while (pendingEngine.snapshot().pendingCandidates() > 0) {
            pendingEngine.tick(scope, origin, List.of(), hitWithKey());
        }
        List<Integer> retainedPendingKeys =
                pendingEngine.cacheEntries().stream()
                        .map(WorldOverlayScanEngine26.CacheEntry::key)
                        .sorted()
                        .toList();
        assertEquals(List.of(1, 70, 80), retainedPendingKeys);

        WorldOverlayScanEngine26<Integer, String> cacheEngine = engine(
                4,
                4,
                4,
                2,
                1_000.0,
                100L
        );
        WorldOverlayScanEngine26.TickResult<Integer, String> capped =
                cacheEngine.tick(
                        scope,
                        origin,
                        List.of(
                                candidate(1, 1.0),
                                candidate(2, 2.0),
                                candidate(3, 3.0),
                                candidate(4, 4.0)
                        ),
                        hitWithKey()
                );

        assertEquals(2, capped.cacheCapacityEvictions());
        assertEquals(2, capped.cacheSize());
        assertEquals(
                List.of(1, 2),
                capped.entries().stream()
                        .map(WorldOverlayScanEngine26.CacheEntry::key)
                        .toList()
        );
    }

    @Test
    void denseHitsUseOneBoundedTrimAndReuseTheImmutableSnapshot() {
        WorldOverlayScanEngine26<Integer, String> engine = engine(
                16_384,
                16_384,
                16_384,
                8_192,
                1_000.0,
                100L
        );
        List<WorldOverlayScanEngine26.Candidate<Integer>> candidates =
                new ArrayList<>(16_384);
        for (int index = 0; index < 16_384; index++) {
            candidates.add(candidate(index, index % 512));
        }

        WorldOverlayScanEngine26.TickResult<Integer, String> result =
                assertTimeout(
                        Duration.ofSeconds(3),
                        () -> engine.tick(
                                scope,
                                origin,
                                candidates,
                                hitWithKey()
                        )
                );

        assertEquals(8_192, result.cacheCapacityEvictions());
        assertEquals(8_192, result.cacheSize());
        assertSame(engine.cacheEntries(), engine.cacheEntries());
    }

    @Test
    void sessionWorldAndDimensionChangesEachResetAllState() {
        WorldOverlayScanEngine26<Integer, String> engine = engine(
                1,
                2,
                4,
                4,
                100.0,
                100L
        );
        WorldOverlayScanEngine26.Scope equalButNewSession =
                new WorldOverlayScanEngine26.Scope(
                        new EqualToken(1),
                        world,
                        "overworld"
                );
        // Establish a value-equal token, then reconnect with another instance.
        WorldOverlayScanEngine26<Integer, String> identityEngine = engine(
                1,
                2,
                4,
                4,
                100.0,
                100L
        );
        EqualToken firstToken = new EqualToken(1);
        WorldOverlayScanEngine26.Scope firstIdentityScope =
                new WorldOverlayScanEngine26.Scope(
                        firstToken,
                        world,
                        "overworld"
                );
        fillCacheAndPending(identityEngine, firstIdentityScope);
        WorldOverlayScanEngine26.TickResult<Integer, String> sessionReset =
                identityEngine.tick(
                        equalButNewSession,
                        origin,
                        List.of(),
                        hitWithKey()
                );
        assertTrue(sessionReset.scopeReset());
        assertEquals(2, sessionReset.scopeEntriesCleared());
        assertEquals(0, sessionReset.cacheSize());
        assertEquals(0, sessionReset.pendingCandidates());

        fillCacheAndPending(engine, scope);
        WorldOverlayScanEngine26.TickResult<Integer, String> worldReset =
                engine.tick(
                        new WorldOverlayScanEngine26.Scope(
                                session,
                                new Object(),
                                "overworld"
                        ),
                        origin,
                        List.of(),
                        hitWithKey()
                );
        assertTrue(worldReset.scopeReset());
        assertEquals(2, worldReset.scopeEntriesCleared());

        WorldOverlayScanEngine26.Scope secondWorldScope =
                new WorldOverlayScanEngine26.Scope(
                        session,
                        new Object(),
                        "overworld"
                );
        WorldOverlayScanEngine26<Integer, String> dimensionEngine = engine(
                1,
                2,
                4,
                4,
                100.0,
                100L
        );
        fillCacheAndPending(dimensionEngine, secondWorldScope);
        WorldOverlayScanEngine26.TickResult<Integer, String> dimensionReset =
                dimensionEngine.tick(
                        new WorldOverlayScanEngine26.Scope(
                                session,
                                secondWorldScope.worldIdentity(),
                                "the_nether"
                        ),
                        origin,
                        List.of(),
                        hitWithKey()
                );
        assertTrue(dimensionReset.scopeReset());
        assertEquals(2, dimensionReset.scopeEntriesCleared());
    }

    @Test
    void distanceFilteringPrunesAdmissionsPendingAndCache() {
        WorldOverlayScanEngine26<Integer, String> engine = engine(
                1,
                4,
                4,
                4,
                10.0,
                100L
        );

        WorldOverlayScanEngine26.TickResult<Integer, String> first =
                engine.tick(
                        scope,
                        origin,
                        List.of(
                                candidate(1, 1.0),
                                candidate(2, 9.0),
                                candidate(3, 10.001)
                        ),
                        hitWithKey()
                );
        assertEquals(1, first.candidatesRejectedByDistance());
        assertEquals(1, first.cacheSize());
        assertEquals(1, first.pendingCandidates());

        WorldOverlayScanEngine26.TickResult<Integer, String> moved =
                engine.tick(
                        scope,
                        new WorldOverlayScanEngine26.Point(
                                100.0,
                                0.0,
                                0.0
                        ),
                        List.of(),
                        hitWithKey()
                );
        assertEquals(1, moved.pendingPrunedByDistance());
        assertEquals(1, moved.cachePrunedByDistance());
        assertEquals(0, moved.pendingCandidates());
        assertEquals(0, moved.cacheSize());
        assertEquals(0, moved.scansPerformed());
    }

    @Test
    void cacheExpiresAtConfiguredAgeAndAHitRefreshesIt() {
        WorldOverlayScanEngine26<Integer, String> engine = engine(
                1,
                1,
                2,
                2,
                100.0,
                2L
        );
        engine.tick(
                scope,
                origin,
                List.of(candidate(1, 1.0)),
                hitWithKey()
        );
        engine.tick(scope, origin, List.of(), hitWithKey());
        engine.tick(
                scope,
                origin,
                List.of(candidate(1, 1.0)),
                hitWithKey()
        );
        engine.tick(scope, origin, List.of(), hitWithKey());
        engine.tick(scope, origin, List.of(), hitWithKey());
        assertEquals(1, engine.snapshot().entries().size());

        WorldOverlayScanEngine26.TickResult<Integer, String> expired =
                engine.tick(scope, origin, List.of(), hitWithKey());
        assertEquals(1, expired.cacheExpired());
        assertEquals(0, expired.cacheSize());
    }

    @Test
    void hitMissAndDeferHaveDistinctCacheAndQueueSemantics() {
        WorldOverlayScanEngine26<Integer, String> engine = engine(
                3,
                3,
                4,
                4,
                100.0,
                100L
        );
        engine.tick(
                scope,
                origin,
                List.of(candidate(1, 1.0)),
                candidate -> WorldOverlayScanEngine26.ProbeResult.hit("old")
        );

        WorldOverlayScanEngine26.TickResult<Integer, String> deferred =
                engine.tick(
                        scope,
                        origin,
                        List.of(
                                candidate(1, 1.0),
                                candidate(2, 2.0)
                        ),
                        candidate -> WorldOverlayScanEngine26.ProbeResult.defer()
                );
        assertEquals(2, deferred.deferred());
        assertEquals(2, deferred.pendingCandidates());
        assertEquals("old", deferred.entries().getFirst().value());

        WorldOverlayScanEngine26.TickResult<Integer, String> resolved =
                engine.tick(
                        scope,
                        origin,
                        List.of(),
                        candidate -> candidate.key() == 1
                                ? WorldOverlayScanEngine26.ProbeResult.miss()
                                : WorldOverlayScanEngine26.ProbeResult.hit(
                                        "new"
                                )
                );
        assertEquals(1, resolved.hits());
        assertEquals(1, resolved.misses());
        assertEquals(0, resolved.pendingCandidates());
        assertEquals(1, resolved.cacheSize());
        assertEquals(2, resolved.entries().getFirst().key());
    }

    @Test
    void invalidationResetAndSnapshotsCannotMutateEngineState() {
        WorldOverlayScanEngine26<Integer, String> engine = engine(
                1,
                2,
                4,
                4,
                100.0,
                100L
        );
        fillCacheAndPending(engine, scope);
        List<WorldOverlayScanEngine26.CacheEntry<Integer, String>> snapshot =
                engine.cacheEntries();

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.clear()
        );
        assertTrue(engine.invalidateCached(1));
        assertFalse(engine.invalidateCached(1));
        assertTrue(engine.forget(2));
        assertEquals(0, engine.snapshot().pendingCandidates());

        engine.reset();
        assertFalse(engine.snapshot().scopePresent());
        assertEquals(0L, engine.snapshot().scopeTick());
        assertEquals(0, engine.snapshot().entries().size());
    }

    @Test
    void configurationAndValueTypesValidateUnsafeInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> engine(0, 1, 1, 1, 1.0, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> engine(2, 2, 1, 1, 1.0, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> engine(1, 1, 1, 0, 1.0, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> engine(1, 1, 1, 1, Double.NaN, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> engine(1, 1, 1, 1, 1.0, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldOverlayScanEngine26.Point(
                        Double.POSITIVE_INFINITY,
                        0.0,
                        0.0
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new WorldOverlayScanEngine26.Scope(
                        null,
                        world,
                        "overworld"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldOverlayScanEngine26.ProbeResult<>(
                        WorldOverlayScanEngine26.ProbeOutcome.MISS,
                        "unexpected"
                )
        );

        WorldOverlayScanEngine26.Configuration defaults =
                WorldOverlayScanEngine26.Configuration.defaults();
        assertTrue(defaults.scanBudgetPerTick() > 0);
        assertTrue(
                defaults.maxPendingCandidates()
                        >= defaults.scanBudgetPerTick()
        );
        assertEquals(0.5, WorldOverlayScanEngine26.Point
                .blockCenter(0, 0, 0).x());
    }

    private void fillCacheAndPending(
            WorldOverlayScanEngine26<Integer, String> engine,
            WorldOverlayScanEngine26.Scope requestedScope
    ) {
        WorldOverlayScanEngine26.TickResult<Integer, String> result =
                engine.tick(
                        requestedScope,
                        origin,
                        List.of(
                                candidate(1, 1.0),
                                candidate(2, 2.0)
                        ),
                        hitWithKey()
                );
        assertEquals(1, result.cacheSize());
        assertEquals(1, result.pendingCandidates());
    }

    private Iterable<WorldOverlayScanEngine26.Candidate<Integer>>
            boundedCountingCandidates(AtomicInteger reads, int count) {
        return () -> new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < count;
            }

            @Override
            public WorldOverlayScanEngine26.Candidate<Integer> next() {
                int key = next++;
                reads.incrementAndGet();
                return candidate(key, key + 1.0);
            }
        };
    }

    private WorldOverlayScanEngine26.Probe<Integer, String> hitWithKey() {
        return candidate -> WorldOverlayScanEngine26.ProbeResult.hit(
                "v" + candidate.key()
        );
    }

    private WorldOverlayScanEngine26.Candidate<Integer> candidate(
            int key,
            double x
    ) {
        return new WorldOverlayScanEngine26.Candidate<>(
                key,
                new WorldOverlayScanEngine26.Point(x, 0.0, 0.0)
        );
    }

    private WorldOverlayScanEngine26<Integer, String> engine(
            int scanBudget,
            int admissionBudget,
            int pendingCap,
            int cacheCap,
            double distance,
            long maxAge
    ) {
        return new WorldOverlayScanEngine26<>(
                new WorldOverlayScanEngine26.Configuration(
                        scanBudget,
                        admissionBudget,
                        pendingCap,
                        cacheCap,
                        distance,
                        maxAge
                )
        );
    }

    private record EqualToken(int value) {
    }
}
