package dev.sealedclient.v26.world;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, conservative state machine for the 26.2 New Chunks module.
 *
 * <p>The engine deliberately detects "first loaded in this client session",
 * not server-side generation. It establishes a multi-sweep join baseline
 * before it can emit {@link Classification#FIRST_SEEN}. A fixed-size Bloom
 * filter can suppress a fresh observation through a false positive, but can
 * never turn an old observation into a fresh one.</p>
 *
 * <p>Every expiry removal and loaded-chunk lookup consumes one operation from
 * the per-tick budget. Only coordinates for which {@link LoadedChunkLookup}
 * returns {@code true} are observed.</p>
 */
public final class NewChunksDecisionEngine26 {
    private static final int BLOOM_BIT_COUNT = 1 << 20;
    private static final int BLOOM_MASK = BLOOM_BIT_COUNT - 1;

    private final BitSet sessionSeen = new BitSet(BLOOM_BIT_COUNT);
    private final LinkedHashMap<Long, MutableObservation> observations =
            new LinkedHashMap<>();

    private SessionKey session;
    private long lastTick = -1L;
    private long sessionStartTick;
    private int requestedCenterX;
    private int requestedCenterZ;
    private Config activeConfig;
    private int sweepCenterX;
    private int sweepCenterZ;
    private int sweepSide;
    private int sweepCursor;
    private int completedBaselineSweeps;
    private boolean baselineEstablished;

    /**
     * Advances at most {@link Config#operationBudget()} bounded operations.
     */
    public TickResult tick(
            SessionKey requestedSession,
            long tick,
            int centerChunkX,
            int centerChunkZ,
            Config config,
            LoadedChunkLookup loadedChunks
    ) {
        Objects.requireNonNull(requestedSession, "requestedSession");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(loadedChunks, "loadedChunks");
        if (tick < 0L) {
            throw new IllegalArgumentException("tick cannot be negative");
        }

        boolean reset = !requestedSession.equals(session)
                || (lastTick >= 0L && tick < lastTick);
        if (reset) {
            reset(requestedSession, tick, centerChunkX, centerChunkZ, config);
        } else if (activeConfig == null
                || activeConfig.scanRadiusChunks() != config.scanRadiusChunks()) {
            // A radius change invalidates the finite sweep geometry, but it
            // must not turn already-seen chunks into fresh observations.
            activeConfig = config;
            beginSweep(centerChunkX, centerChunkZ, config.scanRadiusChunks());
        } else {
            activeConfig = config;
        }

        lastTick = tick;
        requestedCenterX = centerChunkX;
        requestedCenterZ = centerChunkZ;

        int operations = 0;
        int lookups = 0;
        int loadedObserved = 0;
        int newlyClassified = 0;
        int expired = 0;
        int sweepsCompleted = 0;

        while (operations < config.operationBudget()
                && pruneOneExpired(tick, config.lifetimeTicks())) {
            operations++;
            expired++;
        }
        while (operations < config.operationBudget()
                && observations.size() > config.maximumEntries()) {
            evictOldest();
            operations++;
        }

        while (operations < config.operationBudget()) {
            if (sweepCursor >= sweepSide * sweepSide) {
                sweepsCompleted++;
                if (!baselineEstablished) {
                    completedBaselineSweeps++;
                    long baselineAge = tick - sessionStartTick;
                    if (completedBaselineSweeps >= config.baselineSweeps()
                            && baselineAge >= config.baselineTicks()) {
                        baselineEstablished = true;
                    }
                }
                beginSweep(
                        requestedCenterX,
                        requestedCenterZ,
                        config.scanRadiusChunks()
                );
            }

            int index = sweepCursor++;
            int offsetX = index % sweepSide - config.scanRadiusChunks();
            int offsetZ = index / sweepSide - config.scanRadiusChunks();
            int chunkX = sweepCenterX + offsetX;
            int chunkZ = sweepCenterZ + offsetZ;

            boolean loaded;
            try {
                loaded = loadedChunks.isLoaded(chunkX, chunkZ);
            } catch (RuntimeException ignored) {
                // A level can replace its chunk cache during a tick. Unknown
                // is treated as unloaded so it cannot create a false claim.
                loaded = false;
            }
            operations++;
            lookups++;
            if (!loaded) {
                continue;
            }

            ObserveResult result = observe(
                    pack(chunkX, chunkZ),
                    tick,
                    config.maximumEntries()
            );
            loadedObserved++;
            if (result == ObserveResult.FIRST_SEEN) {
                newlyClassified++;
            }
        }

        return new TickResult(
                operations,
                lookups,
                loadedObserved,
                newlyClassified,
                expired,
                sweepsCompleted,
                baselineEstablished,
                sweepCursor,
                sweepSide * sweepSide,
                observations.size(),
                reset
        );
    }

    /**
     * Returns a detached, immutable snapshot in first-observed order.
     */
    public List<ChunkSnapshot> snapshot() {
        List<ChunkSnapshot> result = new ArrayList<>(observations.size());
        for (MutableObservation observation : observations.values()) {
            result.add(observation.snapshot());
        }
        return List.copyOf(result);
    }

    /**
     * Returns only conservative post-baseline observations.
     */
    public List<ChunkSnapshot> freshlyObservedSnapshot() {
        List<ChunkSnapshot> result = new ArrayList<>();
        for (MutableObservation observation : observations.values()) {
            if (observation.classification == Classification.FIRST_SEEN) {
                result.add(observation.snapshot());
            }
        }
        return List.copyOf(result);
    }

    public Status status() {
        return new Status(
                session,
                lastTick,
                completedBaselineSweeps,
                baselineEstablished,
                sweepCursor,
                sweepSide * sweepSide,
                observations.size()
        );
    }

    public void clear() {
        session = null;
        lastTick = -1L;
        sessionStartTick = 0L;
        requestedCenterX = 0;
        requestedCenterZ = 0;
        activeConfig = null;
        sweepCenterX = 0;
        sweepCenterZ = 0;
        sweepSide = 0;
        sweepCursor = 0;
        completedBaselineSweeps = 0;
        baselineEstablished = false;
        sessionSeen.clear();
        observations.clear();
    }

    private void reset(
            SessionKey requestedSession,
            long tick,
            int centerChunkX,
            int centerChunkZ,
            Config config
    ) {
        clear();
        session = requestedSession;
        lastTick = tick;
        sessionStartTick = tick;
        requestedCenterX = centerChunkX;
        requestedCenterZ = centerChunkZ;
        activeConfig = config;
        beginSweep(centerChunkX, centerChunkZ, config.scanRadiusChunks());
    }

    private void beginSweep(int centerChunkX, int centerChunkZ, int radius) {
        sweepCenterX = centerChunkX;
        sweepCenterZ = centerChunkZ;
        sweepSide = radius * 2 + 1;
        sweepCursor = 0;
    }

    private ObserveResult observe(long packedPosition, long tick, int maximumEntries) {
        MutableObservation existing = observations.get(packedPosition);
        if (existing != null) {
            existing.lastObservedTick = tick;
            addToSeenFilter(packedPosition);
            return ObserveResult.DUPLICATE;
        }

        boolean possiblySeen = mightHaveSeen(packedPosition);
        addToSeenFilter(packedPosition);
        Classification classification = baselineEstablished && !possiblySeen
                ? Classification.FIRST_SEEN
                : Classification.BASELINE;
        observations.put(
                packedPosition,
                new MutableObservation(
                        packedPosition,
                        classification,
                        tick,
                        tick
                )
        );
        enforceMaximumEntries(maximumEntries);
        return classification == Classification.FIRST_SEEN
                ? ObserveResult.FIRST_SEEN
                : ObserveResult.BASELINE;
    }

    private boolean pruneOneExpired(long tick, long lifetimeTicks) {
        Iterator<Map.Entry<Long, MutableObservation>> iterator =
                observations.entrySet().iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        MutableObservation oldest = iterator.next().getValue();
        if (tick - oldest.firstObservedTick <= lifetimeTicks) {
            return false;
        }
        iterator.remove();
        return true;
    }

    private void enforceMaximumEntries(int maximumEntries) {
        while (observations.size() > maximumEntries) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Iterator<Long> iterator = observations.keySet().iterator();
        if (!iterator.hasNext()) {
            return;
        }
        iterator.next();
        iterator.remove();
    }

    private boolean mightHaveSeen(long packedPosition) {
        int first = bloomIndex(mix64(packedPosition));
        int second = bloomIndex(
                mix64(packedPosition ^ 0x9E3779B97F4A7C15L)
        );
        int third = bloomIndex(
                mix64(packedPosition ^ 0xD1B54A32D192ED03L)
        );
        return sessionSeen.get(first)
                && sessionSeen.get(second)
                && sessionSeen.get(third);
    }

    private void addToSeenFilter(long packedPosition) {
        sessionSeen.set(bloomIndex(mix64(packedPosition)));
        sessionSeen.set(bloomIndex(
                mix64(packedPosition ^ 0x9E3779B97F4A7C15L)
        ));
        sessionSeen.set(bloomIndex(
                mix64(packedPosition ^ 0xD1B54A32D192ED03L)
        ));
    }

    private static int bloomIndex(long value) {
        return (int) value & BLOOM_MASK;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFF_FFFFL)
                | (((long) chunkZ & 0xFFFF_FFFFL) << 32);
    }

    private static int unpackX(long packed) {
        return (int) packed;
    }

    private static int unpackZ(long packed) {
        return (int) (packed >>> 32);
    }

    @FunctionalInterface
    public interface LoadedChunkLookup {
        boolean isLoaded(int chunkX, int chunkZ);
    }

    public enum Classification {
        BASELINE,
        FIRST_SEEN
    }

    private enum ObserveResult {
        BASELINE,
        FIRST_SEEN,
        DUPLICATE
    }

    public record SessionKey(long connectionEpoch, String dimension) {
        public SessionKey {
            if (connectionEpoch < 0L) {
                throw new IllegalArgumentException(
                        "connectionEpoch cannot be negative"
                );
            }
            dimension = requireText(dimension, "dimension");
        }
    }

    public record Config(
            int scanRadiusChunks,
            int operationBudget,
            long lifetimeTicks,
            int maximumEntries,
            int baselineSweeps,
            long baselineTicks
    ) {
        public Config {
            if (scanRadiusChunks < 0 || scanRadiusChunks > 32) {
                throw new IllegalArgumentException(
                        "scanRadiusChunks must be between 0 and 32"
                );
            }
            if (operationBudget < 1 || operationBudget > 8_192) {
                throw new IllegalArgumentException(
                        "operationBudget must be between 1 and 8192"
                );
            }
            if (lifetimeTicks < 1L || lifetimeTicks > 1_000_000L) {
                throw new IllegalArgumentException(
                        "lifetimeTicks must be between 1 and 1000000"
                );
            }
            if (maximumEntries < 1 || maximumEntries > 8_192) {
                throw new IllegalArgumentException(
                        "maximumEntries must be between 1 and 8192"
                );
            }
            if (baselineSweeps < 1 || baselineSweeps > 64) {
                throw new IllegalArgumentException(
                        "baselineSweeps must be between 1 and 64"
                );
            }
            if (baselineTicks < 0L || baselineTicks > 12_000L) {
                throw new IllegalArgumentException(
                        "baselineTicks must be between 0 and 12000"
                );
            }
        }

        public static Config defaults() {
            return new Config(12, 128, 6_000L, 1_024, 2, 20L);
        }
    }

    public record ChunkSnapshot(
            int chunkX,
            int chunkZ,
            int minimumBlockX,
            int minimumBlockZ,
            Classification classification,
            long firstObservedTick,
            long lastObservedTick
    ) {
        public ChunkSnapshot {
            Objects.requireNonNull(classification, "classification");
            if (firstObservedTick < 0L
                    || lastObservedTick < firstObservedTick) {
                throw new IllegalArgumentException(
                        "invalid observation ticks"
                );
            }
        }
    }

    public record TickResult(
            int operations,
            int loadedChunkLookups,
            int loadedChunksObserved,
            int newlyClassified,
            int expiredEntries,
            int sweepsCompleted,
            boolean baselineEstablished,
            int sweepCursor,
            int sweepSize,
            int retainedEntries,
            boolean sessionReset
    ) {
    }

    public record Status(
            SessionKey session,
            long lastTick,
            int completedBaselineSweeps,
            boolean baselineEstablished,
            int sweepCursor,
            int sweepSize,
            int retainedEntries
    ) {
    }

    private static final class MutableObservation {
        private final long packedPosition;
        private final Classification classification;
        private final long firstObservedTick;
        private long lastObservedTick;

        private MutableObservation(
                long packedPosition,
                Classification classification,
                long firstObservedTick,
                long lastObservedTick
        ) {
            this.packedPosition = packedPosition;
            this.classification = classification;
            this.firstObservedTick = firstObservedTick;
            this.lastObservedTick = lastObservedTick;
        }

        private ChunkSnapshot snapshot() {
            int chunkX = unpackX(packedPosition);
            int chunkZ = unpackZ(packedPosition);
            return new ChunkSnapshot(
                    chunkX,
                    chunkZ,
                    chunkX << 4,
                    chunkZ << 4,
                    classification,
                    firstObservedTick,
                    lastObservedTick
            );
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(name + " must be 1..256 chars");
        }
        return normalized;
    }
}
