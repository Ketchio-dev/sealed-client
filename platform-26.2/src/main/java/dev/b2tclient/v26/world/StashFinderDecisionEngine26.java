package dev.b2tclient.v26.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure incremental decision engine for the 26.2 Stash Finder module.
 *
 * <p>Chunk-loaded checks, block-entity inspections, clustering, candidate
 * matching, expiry, and immutable snapshot publication all share one hard
 * per-tick operation budget. The engine calls
 * {@link LoadedChunkSource#blockEntitiesInLoadedChunk(int, int)} only after
 * {@link LoadedChunkSource#isLoaded(int, int)} returned {@code true} for that
 * coordinate.</p>
 *
 * <p>Only allow-listed container block entities located in the chunk that
 * supplied them are accepted. Positions are deduplicated within each sweep,
 * clusters are not published until the finite sweep reaches its publication
 * phase, and session/dimension changes discard every cached result.</p>
 */
public final class StashFinderDecisionEngine26 {
    private final LinkedHashMap<Long, ChunkFinding> sweepFindings =
            new LinkedHashMap<>();
    private final LinkedHashMap<Long, StoredStash> sessionStashes =
            new LinkedHashMap<>();

    private SessionKey session;
    private long lastTick = -1L;
    private long nextStashId = 1L;
    private Config activeConfig;
    private int requestedOriginX;
    private int requestedOriginY;
    private int requestedOriginZ;

    private Phase phase = Phase.SCANNING;
    private int sweepOriginX;
    private int sweepOriginY;
    private int sweepOriginZ;
    private int sweepChunkRadius;
    private int sweepSide;
    private int sweepChunkCursor;
    private int sweepChunkCount;
    private int activeChunkX;
    private int activeChunkZ;
    private Iterator<BlockObservation> activeBlockEntities;
    private long inspectedBlockEntities;
    private int uniqueContainerObservations;
    private boolean sweepLimited;
    private boolean lastPublishedLimited;
    private boolean baselineEstablished;

    private Iterator<Map.Entry<Long, ChunkFinding>> clusterSeeds;
    private final Set<Long> clusterVisited = new HashSet<>();
    private final ArrayDeque<Long> clusterPending = new ArrayDeque<>();
    private ClusterAccumulator activeCluster;
    private final List<ClusterCandidate> candidates = new ArrayList<>();

    private int candidateCursor;
    private ClusterCandidate activeCandidate;
    private Iterator<Map.Entry<Long, StoredStash>> stashSearch;
    private Long bestMatchingStash;
    private long bestMatchingDistance;

    private Iterator<StoredStash> snapshotIterator;
    private LinkedHashMap<Long, StashSnapshot> snapshotBuilder;
    private LinkedHashMap<Long, StashSnapshot> publishedStashes =
            new LinkedHashMap<>();

    /**
     * Advances one world scan by no more than the configured operation budget.
     */
    public TickResult tick(
            SessionKey requestedSession,
            long tick,
            int originX,
            int originY,
            int originZ,
            Config config,
            LoadedChunkSource chunks
    ) {
        Objects.requireNonNull(requestedSession, "requestedSession");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(chunks, "chunks");
        if (tick < 0L) {
            throw new IllegalArgumentException("tick cannot be negative");
        }

        boolean reset = !requestedSession.equals(session)
                || (lastTick >= 0L && tick < lastTick);
        if (reset) {
            reset(
                    requestedSession,
                    tick,
                    originX,
                    originY,
                    originZ,
                    config
            );
        }
        lastTick = tick;
        requestedOriginX = originX;
        requestedOriginY = originY;
        requestedOriginZ = originZ;

        if (!reset && shouldRestartSweep(originX, originZ, config)) {
            activeConfig = config;
            beginSweep(originX, originY, originZ, config);
        } else {
            activeConfig = config;
        }

        Counters counters = new Counters();
        while (counters.operations < config.operationBudget()) {
            if (canMutateStashMap()) {
                if (pruneOneExpired(tick, config.lifetimeTicks())) {
                    counters.operations++;
                    counters.expired++;
                    continue;
                }
                if (sessionStashes.size() > config.maximumEntries()) {
                    evictOldestStash();
                    counters.operations++;
                    counters.evicted++;
                    continue;
                }
            }

            boolean progressed = switch (phase) {
                case SCANNING -> stepScanning(chunks, config, counters);
                case CLUSTERING -> stepClustering(counters);
                case MERGING -> stepMerging(tick, config, counters);
                case SNAPSHOTTING -> stepSnapshotting(config, counters);
            };
            if (!progressed) {
                // State transitions do not consume budget. Each transition is
                // finite, but guard against a future empty-state regression.
                counters.zeroCostTransitions++;
                if (counters.zeroCostTransitions > 16) {
                    break;
                }
            } else {
                counters.zeroCostTransitions = 0;
            }
        }

        return new TickResult(
                counters.operations,
                counters.chunkLookups,
                counters.blockEntitiesInspected,
                counters.clusterOperations,
                counters.mergeOperations,
                counters.snapshotOperations,
                counters.expired,
                counters.evicted,
                counters.publishedSweeps,
                phase,
                sweepChunkCursor,
                sweepChunkCount,
                uniqueContainerObservations,
                sweepLimited || lastPublishedLimited,
                sessionStashes.size(),
                publishedStashes.size(),
                baselineEstablished,
                reset
        );
    }

    /**
     * Returns the last fully published immutable snapshot.
     */
    public List<StashSnapshot> snapshot() {
        return List.copyOf(publishedStashes.values());
    }

    public ScanProgress scanProgress() {
        return new ScanProgress(
                phase,
                sweepChunkCursor,
                sweepChunkCount,
                inspectedBlockEntities,
                uniqueContainerObservations,
                sweepOriginX,
                sweepOriginY,
                sweepOriginZ,
                sweepLimited,
                lastPublishedLimited,
                baselineEstablished
        );
    }

    public void clear() {
        session = null;
        lastTick = -1L;
        nextStashId = 1L;
        activeConfig = null;
        requestedOriginX = 0;
        requestedOriginY = 0;
        requestedOriginZ = 0;
        phase = Phase.SCANNING;
        sweepOriginX = 0;
        sweepOriginY = 0;
        sweepOriginZ = 0;
        sweepChunkRadius = 0;
        sweepSide = 0;
        sweepChunkCursor = 0;
        sweepChunkCount = 0;
        activeChunkX = 0;
        activeChunkZ = 0;
        activeBlockEntities = null;
        inspectedBlockEntities = 0L;
        uniqueContainerObservations = 0;
        sweepLimited = false;
        lastPublishedLimited = false;
        baselineEstablished = false;
        clearPublicationState();
        sweepFindings.clear();
        sessionStashes.clear();
        publishedStashes.clear();
    }

    private void reset(
            SessionKey requestedSession,
            long tick,
            int originX,
            int originY,
            int originZ,
            Config config
    ) {
        clear();
        session = requestedSession;
        lastTick = tick;
        requestedOriginX = originX;
        requestedOriginY = originY;
        requestedOriginZ = originZ;
        activeConfig = config;
        beginSweep(originX, originY, originZ, config);
    }

    private boolean shouldRestartSweep(
            int originX,
            int originZ,
            Config config
    ) {
        if (activeConfig == null) {
            return true;
        }
        if (activeConfig.rangeBlocks() != config.rangeBlocks()
                || activeConfig.minimumContainers()
                != config.minimumContainers()
                || activeConfig.maximumContainerObservations()
                != config.maximumContainerObservations()) {
            return true;
        }
        long dx = (long) originX - sweepOriginX;
        long dz = (long) originZ - sweepOriginZ;
        long range = config.rangeBlocks();
        return dx * dx + dz * dz > range * range;
    }

    private void beginSweep(
            int originX,
            int originY,
            int originZ,
            Config config
    ) {
        phase = Phase.SCANNING;
        sweepOriginX = originX;
        sweepOriginY = originY;
        sweepOriginZ = originZ;
        sweepChunkRadius = (config.rangeBlocks() + 15) / 16;
        sweepSide = sweepChunkRadius * 2 + 1;
        sweepChunkCursor = 0;
        sweepChunkCount = sweepSide * sweepSide;
        activeChunkX = 0;
        activeChunkZ = 0;
        activeBlockEntities = null;
        inspectedBlockEntities = 0L;
        uniqueContainerObservations = 0;
        sweepLimited = false;
        sweepFindings.clear();
        clearPublicationState();
    }

    private void clearPublicationState() {
        clusterSeeds = null;
        clusterVisited.clear();
        clusterPending.clear();
        activeCluster = null;
        candidates.clear();
        candidateCursor = 0;
        activeCandidate = null;
        stashSearch = null;
        bestMatchingStash = null;
        bestMatchingDistance = Long.MAX_VALUE;
        snapshotIterator = null;
        snapshotBuilder = null;
    }

    private boolean stepScanning(
            LoadedChunkSource chunks,
            Config config,
            Counters counters
    ) {
        if (activeBlockEntities != null) {
            BlockObservation observation;
            try {
                if (!activeBlockEntities.hasNext()) {
                    activeBlockEntities = null;
                    return false;
                }
                observation = activeBlockEntities.next();
            } catch (RuntimeException ignored) {
                // Chunk replacement can invalidate a live block-entity
                // iterator. Dropping it is conservative and terminates work.
                activeBlockEntities = null;
                counters.operations++;
                counters.blockEntitiesInspected++;
                return true;
            }
            counters.operations++;
            counters.blockEntitiesInspected++;
            inspectedBlockEntities = saturatingIncrement(
                    inspectedBlockEntities
            );
            inspect(observation, config);
            return true;
        }

        if (sweepChunkCursor >= sweepChunkCount) {
            phase = Phase.CLUSTERING;
            clusterSeeds = sweepFindings.entrySet().iterator();
            return false;
        }

        int index = sweepChunkCursor++;
        int offsetX = index % sweepSide - sweepChunkRadius;
        int offsetZ = index / sweepSide - sweepChunkRadius;
        int chunkX = Math.floorDiv(sweepOriginX, 16) + offsetX;
        int chunkZ = Math.floorDiv(sweepOriginZ, 16) + offsetZ;

        counters.operations++;
        counters.chunkLookups++;
        boolean loaded;
        try {
            loaded = chunks.isLoaded(chunkX, chunkZ);
        } catch (RuntimeException ignored) {
            loaded = false;
        }
        if (!loaded) {
            return true;
        }

        activeChunkX = chunkX;
        activeChunkZ = chunkZ;
        try {
            activeBlockEntities = Objects.requireNonNullElseGet(
                    chunks.blockEntitiesInLoadedChunk(chunkX, chunkZ),
                    List::<BlockObservation>of
            ).iterator();
        } catch (RuntimeException ignored) {
            activeBlockEntities = null;
        }
        return true;
    }

    private void inspect(BlockObservation observation, Config config) {
        if (observation == null
                || observation.kind() == ContainerKind.IGNORED
                || Math.floorDiv(observation.blockX(), 16) != activeChunkX
                || Math.floorDiv(observation.blockZ(), 16) != activeChunkZ) {
            return;
        }

        long dx = (long) observation.blockX() - sweepOriginX;
        long dz = (long) observation.blockZ() - sweepOriginZ;
        long range = config.rangeBlocks();
        if (dx * dx + dz * dz > range * range) {
            return;
        }

        long packedChunk = packChunk(activeChunkX, activeChunkZ);
        ChunkFinding finding = sweepFindings.get(packedChunk);
        BlockPosition position = new BlockPosition(
                observation.blockX(),
                observation.blockY(),
                observation.blockZ()
        );
        if (finding != null && finding.contains(position)) {
            return;
        }
        if (uniqueContainerObservations
                >= config.maximumContainerObservations()) {
            sweepLimited = true;
            return;
        }
        if (finding == null) {
            finding = new ChunkFinding();
            sweepFindings.put(packedChunk, finding);
        }
        if (finding.add(position, observation.kind())) {
            uniqueContainerObservations++;
        }
    }

    private boolean stepClustering(Counters counters) {
        if (!clusterPending.isEmpty()) {
            long packedChunk = clusterPending.removeFirst();
            ChunkFinding finding = sweepFindings.get(packedChunk);
            if (finding != null) {
                int chunkX = unpackChunkX(packedChunk);
                int chunkZ = unpackChunkZ(packedChunk);
                activeCluster.add(chunkX, chunkZ, finding);
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                        if (offsetX == 0 && offsetZ == 0) {
                            continue;
                        }
                        long neighbour = packChunk(
                                chunkX + offsetX,
                                chunkZ + offsetZ
                        );
                        if (sweepFindings.containsKey(neighbour)
                                && clusterVisited.add(neighbour)) {
                            clusterPending.addLast(neighbour);
                        }
                    }
                }
            }
            counters.operations++;
            counters.clusterOperations++;
            if (clusterPending.isEmpty() && activeCluster != null) {
                candidates.add(activeCluster.finish());
                activeCluster = null;
            }
            return true;
        }

        if (clusterSeeds != null && clusterSeeds.hasNext()) {
            Map.Entry<Long, ChunkFinding> seed = clusterSeeds.next();
            counters.operations++;
            counters.clusterOperations++;
            if (clusterVisited.add(seed.getKey())) {
                activeCluster = new ClusterAccumulator();
                clusterPending.addLast(seed.getKey());
            }
            return true;
        }

        phase = Phase.MERGING;
        clusterSeeds = null;
        candidateCursor = 0;
        return false;
    }

    private boolean stepMerging(
            long tick,
            Config config,
            Counters counters
    ) {
        if (activeCandidate == null) {
            if (candidateCursor >= candidates.size()) {
                baselineEstablished = true;
                phase = Phase.SNAPSHOTTING;
                return false;
            }

            ClusterCandidate candidate = candidates.get(candidateCursor++);
            counters.operations++;
            counters.mergeOperations++;
            if (candidate.containerCount()
                    < config.minimumContainers()) {
                return true;
            }
            activeCandidate = candidate;
            stashSearch = sessionStashes.entrySet().iterator();
            bestMatchingStash = null;
            bestMatchingDistance = Long.MAX_VALUE;
            return true;
        }

        if (stashSearch != null && stashSearch.hasNext()) {
            Map.Entry<Long, StoredStash> entry = stashSearch.next();
            counters.operations++;
            counters.mergeOperations++;
            StoredStash stash = entry.getValue();
            if (stash.touches(activeCandidate)) {
                long distance = stash.distanceSquared(activeCandidate);
                if (distance < bestMatchingDistance
                        || (distance == bestMatchingDistance
                        && (bestMatchingStash == null
                        || entry.getKey() < bestMatchingStash))) {
                    bestMatchingStash = entry.getKey();
                    bestMatchingDistance = distance;
                }
            }
            return true;
        }

        stashSearch = null;
        mergeActiveCandidate(tick, config.maximumEntries());
        counters.operations++;
        counters.mergeOperations++;
        activeCandidate = null;
        return true;
    }

    private void mergeActiveCandidate(long tick, int maximumEntries) {
        if (bestMatchingStash == null) {
            Evidence evidence = baselineEstablished
                    ? Evidence.FIRST_SEEN
                    : Evidence.BASELINE;
            StoredStash stash = StoredStash.from(
                    nextStashId,
                    session.dimension(),
                    evidence,
                    activeCandidate,
                    tick
            );
            nextStashId = saturatingIncrement(nextStashId);
            sessionStashes.put(stash.id, stash);
            enforceStashMaximum(maximumEntries);
            return;
        }

        StoredStash existing = sessionStashes.remove(bestMatchingStash);
        if (existing != null) {
            sessionStashes.put(
                    existing.id,
                    existing.merge(activeCandidate, tick)
            );
        }
    }

    private boolean stepSnapshotting(Config config, Counters counters) {
        if (snapshotIterator == null) {
            snapshotBuilder = new LinkedHashMap<>(
                    Math.min(sessionStashes.size(), config.maximumEntries())
            );
            snapshotIterator = sessionStashes.values().iterator();
            return false;
        }

        if (snapshotIterator.hasNext()) {
            StoredStash stash = snapshotIterator.next();
            if (stash.containerCount >= config.minimumContainers()) {
                StashSnapshot snapshot = stash.snapshot();
                snapshotBuilder.put(snapshot.id(), snapshot);
            }
            counters.operations++;
            counters.snapshotOperations++;
            return true;
        }

        publishedStashes = snapshotBuilder;
        lastPublishedLimited = sweepLimited;
        counters.publishedSweeps++;
        beginSweep(
                requestedOriginX,
                requestedOriginY,
                requestedOriginZ,
                config
        );
        return false;
    }

    private boolean canMutateStashMap() {
        return !((phase == Phase.MERGING && stashSearch != null)
                || (phase == Phase.SNAPSHOTTING
                && snapshotIterator != null));
    }

    private boolean pruneOneExpired(long tick, long lifetimeTicks) {
        Iterator<Map.Entry<Long, StoredStash>> iterator =
                sessionStashes.entrySet().iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        StoredStash oldest = iterator.next().getValue();
        if (tick - oldest.lastObservedTick <= lifetimeTicks) {
            return false;
        }
        iterator.remove();
        publishedStashes.remove(oldest.id);
        return true;
    }

    private void enforceStashMaximum(int maximum) {
        while (sessionStashes.size() > maximum) {
            evictOldestStash();
        }
    }

    private void evictOldestStash() {
        Iterator<Long> iterator = sessionStashes.keySet().iterator();
        if (!iterator.hasNext()) {
            return;
        }
        Long id = iterator.next();
        iterator.remove();
        publishedStashes.remove(id);
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFF_FFFFL)
                | (((long) chunkZ & 0xFFFF_FFFFL) << 32);
    }

    private static int unpackChunkX(long packed) {
        return (int) packed;
    }

    private static int unpackChunkZ(long packed) {
        return (int) (packed >>> 32);
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    public interface LoadedChunkSource {
        boolean isLoaded(int chunkX, int chunkZ);

        Iterable<BlockObservation> blockEntitiesInLoadedChunk(
                int chunkX,
                int chunkZ
        );
    }

    public enum ContainerKind {
        CHEST,
        TRAPPED_CHEST,
        ENDER_CHEST,
        BARREL,
        SHULKER_BOX,
        HOPPER,
        DISPENSER,
        DROPPER,
        FURNACE,
        BLAST_FURNACE,
        SMOKER,
        BREWING_STAND,
        CRAFTER,
        IGNORED
    }

    public enum Evidence {
        BASELINE,
        FIRST_SEEN
    }

    public enum Phase {
        SCANNING,
        CLUSTERING,
        MERGING,
        SNAPSHOTTING
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
            int rangeBlocks,
            int operationBudget,
            int minimumContainers,
            int maximumEntries,
            int maximumContainerObservations,
            long lifetimeTicks
    ) {
        public Config {
            if (rangeBlocks < 16 || rangeBlocks > 512) {
                throw new IllegalArgumentException(
                        "rangeBlocks must be between 16 and 512"
                );
            }
            if (operationBudget < 1 || operationBudget > 8_192) {
                throw new IllegalArgumentException(
                        "operationBudget must be between 1 and 8192"
                );
            }
            if (minimumContainers < 2 || minimumContainers > 512) {
                throw new IllegalArgumentException(
                        "minimumContainers must be between 2 and 512"
                );
            }
            if (maximumEntries < 1 || maximumEntries > 1_024) {
                throw new IllegalArgumentException(
                        "maximumEntries must be between 1 and 1024"
                );
            }
            if (maximumContainerObservations < minimumContainers
                    || maximumContainerObservations > 65_536) {
                throw new IllegalArgumentException(
                        "maximumContainerObservations must be between "
                                + "minimumContainers and 65536"
                );
            }
            if (lifetimeTicks < 1L || lifetimeTicks > 1_000_000L) {
                throw new IllegalArgumentException(
                        "lifetimeTicks must be between 1 and 1000000"
                );
            }
        }

        public static Config defaults() {
            return new Config(
                    256,
                    128,
                    6,
                    128,
                    16_384,
                    18_000L
            );
        }
    }

    public record BlockObservation(
            int blockX,
            int blockY,
            int blockZ,
            ContainerKind kind
    ) {
        public BlockObservation {
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record StashSnapshot(
            long id,
            String dimension,
            Evidence evidence,
            double centerX,
            double centerY,
            double centerZ,
            int minimumChunkX,
            int maximumChunkX,
            int minimumChunkZ,
            int maximumChunkZ,
            int containerCount,
            Map<ContainerKind, Integer> containerCounts,
            long firstObservedTick,
            long lastObservedTick
    ) {
        public StashSnapshot {
            if (id < 1L) {
                throw new IllegalArgumentException("id must be positive");
            }
            dimension = requireText(dimension, "dimension");
            Objects.requireNonNull(evidence, "evidence");
            if (!Double.isFinite(centerX)
                    || !Double.isFinite(centerY)
                    || !Double.isFinite(centerZ)
                    || minimumChunkX > maximumChunkX
                    || minimumChunkZ > maximumChunkZ
                    || containerCount < 1
                    || firstObservedTick < 0L
                    || lastObservedTick < firstObservedTick) {
                throw new IllegalArgumentException("invalid stash snapshot");
            }
            containerCounts = Map.copyOf(
                    Objects.requireNonNull(
                            containerCounts,
                            "containerCounts"
                    )
            );
        }
    }

    public record ScanProgress(
            Phase phase,
            int scannedChunks,
            int totalChunks,
            long inspectedBlockEntities,
            int uniqueContainerObservations,
            int originX,
            int originY,
            int originZ,
            boolean observationLimitReached,
            boolean lastPublishedObservationLimitReached,
            boolean baselineEstablished
    ) {
    }

    public record TickResult(
            int operations,
            int chunkLookups,
            int blockEntitiesInspected,
            int clusterOperations,
            int mergeOperations,
            int snapshotOperations,
            int expiredEntries,
            int evictedEntries,
            int publishedSweeps,
            Phase phase,
            int scannedChunks,
            int totalChunks,
            int uniqueContainerObservations,
            boolean observationLimitReached,
            int retainedStashes,
            int publishedStashes,
            boolean baselineEstablished,
            boolean sessionReset
    ) {
    }

    private record BlockPosition(int x, int y, int z) {
    }

    private static final class ChunkFinding {
        private final LinkedHashMap<BlockPosition, ContainerKind> containers =
                new LinkedHashMap<>();
        private final EnumMap<ContainerKind, Integer> counts =
                new EnumMap<>(ContainerKind.class);
        private long xSum;
        private long ySum;
        private long zSum;

        private boolean contains(BlockPosition position) {
            return containers.containsKey(position);
        }

        private boolean add(BlockPosition position, ContainerKind kind) {
            if (containers.putIfAbsent(position, kind) != null) {
                return false;
            }
            counts.merge(kind, 1, Integer::sum);
            xSum += position.x();
            ySum += position.y();
            zSum += position.z();
            return true;
        }

        private int containerCount() {
            return containers.size();
        }
    }

    private static final class ClusterAccumulator {
        private final EnumMap<ContainerKind, Integer> counts =
                new EnumMap<>(ContainerKind.class);
        private int minimumChunkX = Integer.MAX_VALUE;
        private int maximumChunkX = Integer.MIN_VALUE;
        private int minimumChunkZ = Integer.MAX_VALUE;
        private int maximumChunkZ = Integer.MIN_VALUE;
        private int containerCount;
        private long xSum;
        private long ySum;
        private long zSum;

        private void add(int chunkX, int chunkZ, ChunkFinding finding) {
            minimumChunkX = Math.min(minimumChunkX, chunkX);
            maximumChunkX = Math.max(maximumChunkX, chunkX);
            minimumChunkZ = Math.min(minimumChunkZ, chunkZ);
            maximumChunkZ = Math.max(maximumChunkZ, chunkZ);
            containerCount += finding.containerCount();
            xSum += finding.xSum;
            ySum += finding.ySum;
            zSum += finding.zSum;
            finding.counts.forEach(
                    (kind, count) -> counts.merge(kind, count, Integer::sum)
            );
        }

        private ClusterCandidate finish() {
            if (containerCount < 1) {
                throw new IllegalStateException("empty cluster");
            }
            return new ClusterCandidate(
                    (double) xSum / containerCount,
                    (double) ySum / containerCount,
                    (double) zSum / containerCount,
                    minimumChunkX,
                    maximumChunkX,
                    minimumChunkZ,
                    maximumChunkZ,
                    containerCount,
                    Map.copyOf(counts)
            );
        }
    }

    private record ClusterCandidate(
            double centerX,
            double centerY,
            double centerZ,
            int minimumChunkX,
            int maximumChunkX,
            int minimumChunkZ,
            int maximumChunkZ,
            int containerCount,
            Map<ContainerKind, Integer> containerCounts
    ) {
    }

    private static final class StoredStash {
        private final long id;
        private final String dimension;
        private final Evidence evidence;
        private final double centerX;
        private final double centerY;
        private final double centerZ;
        private final int minimumChunkX;
        private final int maximumChunkX;
        private final int minimumChunkZ;
        private final int maximumChunkZ;
        private final int containerCount;
        private final Map<ContainerKind, Integer> containerCounts;
        private final long firstObservedTick;
        private final long lastObservedTick;

        private StoredStash(
                long id,
                String dimension,
                Evidence evidence,
                double centerX,
                double centerY,
                double centerZ,
                int minimumChunkX,
                int maximumChunkX,
                int minimumChunkZ,
                int maximumChunkZ,
                int containerCount,
                Map<ContainerKind, Integer> containerCounts,
                long firstObservedTick,
                long lastObservedTick
        ) {
            this.id = id;
            this.dimension = dimension;
            this.evidence = evidence;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.minimumChunkX = minimumChunkX;
            this.maximumChunkX = maximumChunkX;
            this.minimumChunkZ = minimumChunkZ;
            this.maximumChunkZ = maximumChunkZ;
            this.containerCount = containerCount;
            this.containerCounts = Map.copyOf(containerCounts);
            this.firstObservedTick = firstObservedTick;
            this.lastObservedTick = lastObservedTick;
        }

        private static StoredStash from(
                long id,
                String dimension,
                Evidence evidence,
                ClusterCandidate candidate,
                long tick
        ) {
            return new StoredStash(
                    id,
                    dimension,
                    evidence,
                    candidate.centerX(),
                    candidate.centerY(),
                    candidate.centerZ(),
                    candidate.minimumChunkX(),
                    candidate.maximumChunkX(),
                    candidate.minimumChunkZ(),
                    candidate.maximumChunkZ(),
                    candidate.containerCount(),
                    candidate.containerCounts(),
                    tick,
                    tick
            );
        }

        private boolean touches(ClusterCandidate candidate) {
            return axisGap(
                    minimumChunkX,
                    maximumChunkX,
                    candidate.minimumChunkX(),
                    candidate.maximumChunkX()
            ) <= 1 && axisGap(
                    minimumChunkZ,
                    maximumChunkZ,
                    candidate.minimumChunkZ(),
                    candidate.maximumChunkZ()
            ) <= 1;
        }

        private long distanceSquared(ClusterCandidate candidate) {
            long dx = Math.round(centerX - candidate.centerX());
            long dz = Math.round(centerZ - candidate.centerZ());
            return dx * dx + dz * dz;
        }

        private StoredStash merge(
                ClusterCandidate candidate,
                long observedTick
        ) {
            boolean candidateIsStronger =
                    candidate.containerCount() >= containerCount;
            return new StoredStash(
                    id,
                    dimension,
                    evidence,
                    candidateIsStronger ? candidate.centerX() : centerX,
                    candidateIsStronger ? candidate.centerY() : centerY,
                    candidateIsStronger ? candidate.centerZ() : centerZ,
                    Math.min(minimumChunkX, candidate.minimumChunkX()),
                    Math.max(maximumChunkX, candidate.maximumChunkX()),
                    Math.min(minimumChunkZ, candidate.minimumChunkZ()),
                    Math.max(maximumChunkZ, candidate.maximumChunkZ()),
                    candidateIsStronger
                            ? candidate.containerCount()
                            : containerCount,
                    candidateIsStronger
                            ? candidate.containerCounts()
                            : containerCounts,
                    firstObservedTick,
                    observedTick
            );
        }

        private StashSnapshot snapshot() {
            return new StashSnapshot(
                    id,
                    dimension,
                    evidence,
                    centerX,
                    centerY,
                    centerZ,
                    minimumChunkX,
                    maximumChunkX,
                    minimumChunkZ,
                    maximumChunkZ,
                    containerCount,
                    containerCounts,
                    firstObservedTick,
                    lastObservedTick
            );
        }

        private static int axisGap(
                int firstMinimum,
                int firstMaximum,
                int secondMinimum,
                int secondMaximum
        ) {
            if (firstMaximum < secondMinimum) {
                return secondMinimum - firstMaximum;
            }
            if (secondMaximum < firstMinimum) {
                return firstMinimum - secondMaximum;
            }
            return 0;
        }
    }

    private static final class Counters {
        private int operations;
        private int chunkLookups;
        private int blockEntitiesInspected;
        private int clusterOperations;
        private int mergeOperations;
        private int snapshotOperations;
        private int expired;
        private int evicted;
        private int publishedSweeps;
        private int zeroCostTransitions;
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
