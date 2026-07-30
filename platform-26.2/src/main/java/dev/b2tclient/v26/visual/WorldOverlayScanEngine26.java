package dev.b2tclient.v26.visual;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, Minecraft-independent scan and cache engine for world overlays.
 *
 * <p>Storage ESP, Hole ESP, and Block ESP can each own an instance with their
 * own key/value types and limits. The engine deliberately knows nothing about
 * chunks, block states, or rendering. Its caller supplies cheap candidate
 * positions and a probe that performs the potentially expensive world lookup.
 * Both candidate intake and probes are strictly limited per tick.</p>
 *
 * <p>Session and world tokens use identity semantics because a respawn or
 * reconnect can produce value-equal objects backed by different state.
 * Dimension keys use value equality. Any of the three changing clears pending
 * work and cached overlay entries before new candidates are admitted.</p>
 *
 * @param <K> stable candidate key, normally an overlay kind plus block position
 * @param <V> immutable render data produced by the world probe
 */
public final class WorldOverlayScanEngine26<K, V> {
    public static final int HARD_MAX_SCAN_BUDGET = 16_384;
    public static final int HARD_MAX_ADMISSION_BUDGET = 32_768;
    public static final int HARD_MAX_PENDING_CANDIDATES = 32_768;
    public static final int HARD_MAX_CACHE_ENTRIES = 8_192;
    public static final double HARD_MAX_DISTANCE_BLOCKS = 2_048.0;
    public static final long HARD_MAX_CACHE_AGE_TICKS = 1_000_000L;

    private final Configuration configuration;
    private final LinkedHashMap<K, Candidate<K>> pending =
            new LinkedHashMap<>();
    private final LinkedHashMap<K, MutableCacheEntry<K, V>> cached =
            new LinkedHashMap<>();

    private Scope scope;
    private long scopeTick;
    private Point observer = new Point(0.0, 0.0, 0.0);
    private List<CacheEntry<K, V>> cachedSnapshot = List.of();

    public WorldOverlayScanEngine26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    public Configuration configuration() {
        return configuration;
    }

    /**
     * Advances exactly one overlay tick.
     *
     * <p>The engine calls at most
     * {@link Configuration#candidateAdmissionBudgetPerTick()} iterator
     * elements and at most {@link Configuration#scanBudgetPerTick()} probes.
     * A lazy candidate iterable is therefore safe and need not materialize the
     * entire world search volume.</p>
     */
    public TickResult<K, V> tick(
            Scope nextScope,
            Point nextObserver,
            Iterable<Candidate<K>> discoveredCandidates,
            Probe<K, V> probe
    ) {
        Scope requestedScope = Objects.requireNonNull(nextScope, "nextScope");
        Point requestedObserver = Objects.requireNonNull(
                nextObserver,
                "nextObserver"
        );
        Iterable<Candidate<K>> discovered = Objects.requireNonNull(
                discoveredCandidates,
                "discoveredCandidates"
        );
        Probe<K, V> worldProbe = Objects.requireNonNull(probe, "probe");

        boolean scopeReset = !sameScope(scope, requestedScope);
        int scopeEntriesCleared = 0;
        if (scopeReset) {
            scopeEntriesCleared = pending.size() + cached.size();
            pending.clear();
            cached.clear();
            cachedSnapshot = List.of();
            scope = requestedScope;
            scopeTick = 0L;
        }
        observer = requestedObserver;
        scopeTick++;

        MutableMetrics metrics = new MutableMetrics();
        metrics.scopeEntriesCleared = scopeEntriesCleared;
        prunePendingByDistance(metrics);
        pruneCache(metrics);
        admit(discovered, metrics);
        try {
            scan(worldProbe, metrics);
        } finally {
            trimCacheToCapacity(metrics);
            rebuildCachedSnapshot();
        }

        return new TickResult<>(
                scopeReset,
                scopeTick,
                metrics.candidatesExamined,
                metrics.candidatesAdmitted,
                metrics.candidatesUpdated,
                metrics.candidatesRejectedByDistance,
                metrics.pendingPrunedByDistance,
                metrics.cachePrunedByDistance,
                metrics.cacheExpired,
                metrics.pendingCapacityEvictions,
                metrics.cacheCapacityEvictions,
                metrics.scansPerformed,
                metrics.hits,
                metrics.misses,
                metrics.deferred,
                metrics.scopeEntriesCleared,
                pending.size(),
                cached.size(),
                configuration.scanBudgetPerTick()
                        - metrics.scansPerformed,
                cachedSnapshot
        );
    }

    /**
     * Removes a cached result immediately without disturbing queued rescans.
     * Useful when a block update invalidates render data before the next probe.
     */
    public boolean invalidateCached(K key) {
        boolean removed =
                cached.remove(Objects.requireNonNull(key, "key")) != null;
        if (removed) {
            rebuildCachedSnapshot();
        }
        return removed;
    }

    /**
     * Removes both cached and queued state for one key.
     */
    public boolean forget(K key) {
        K requiredKey = Objects.requireNonNull(key, "key");
        boolean removedPending = pending.remove(requiredKey) != null;
        boolean removedCached = cached.remove(requiredKey) != null;
        if (removedCached) {
            rebuildCachedSnapshot();
        }
        return removedPending || removedCached;
    }

    /**
     * Clears all state and requires the next tick to establish a new scope.
     */
    public void reset() {
        pending.clear();
        cached.clear();
        cachedSnapshot = List.of();
        scope = null;
        scopeTick = 0L;
        observer = new Point(0.0, 0.0, 0.0);
    }

    public Snapshot<K, V> snapshot() {
        return new Snapshot<>(
                scope != null,
                scopeTick,
                pending.size(),
                cachedSnapshot
        );
    }

    public List<CacheEntry<K, V>> cacheEntries() {
        return cachedSnapshot;
    }

    private void admit(
            Iterable<Candidate<K>> discovered,
            MutableMetrics metrics
    ) {
        Iterator<Candidate<K>> iterator = discovered.iterator();
        int admissionBudget =
                configuration.candidateAdmissionBudgetPerTick();
        while (metrics.candidatesExamined < admissionBudget
                && iterator.hasNext()) {
            Candidate<K> candidate = Objects.requireNonNull(
                    iterator.next(),
                    "candidate"
            );
            metrics.candidatesExamined++;

            if (!withinDistance(candidate.position())) {
                metrics.candidatesRejectedByDistance++;
                continue;
            }

            Candidate<K> existing = pending.get(candidate.key());
            if (existing != null) {
                pending.put(candidate.key(), candidate);
                metrics.candidatesUpdated++;
                continue;
            }

            /*
             * Admit into a per-tick staging overflow, then trim once. Finding
             * the farthest entry for every candidate turns a full 16k budget
             * into hundreds of millions of distance checks. One stable sort
             * keeps the same nearest-first capacity policy at
             * O((pending + admission) log n), with the transient size still
             * bounded by the two configured hard budgets.
             */
            pending.put(candidate.key(), candidate);
            metrics.candidatesAdmitted++;
        }

        int maximum = configuration.maxPendingCandidates();
        if (pending.size() > maximum) {
            List<Map.Entry<K, Candidate<K>>> nearest =
                    new ArrayList<>(pending.entrySet());
            nearest.sort(
                    java.util.Comparator.comparingDouble(
                            entry -> distanceSquared(
                                    entry.getValue().position()
                            )
                    )
            );
            int evictions = pending.size() - maximum;
            pending.clear();
            for (int index = 0; index < maximum; index++) {
                Map.Entry<K, Candidate<K>> entry = nearest.get(index);
                pending.put(entry.getKey(), entry.getValue());
            }
            metrics.pendingCapacityEvictions += evictions;
        }
    }

    private void scan(Probe<K, V> probe, MutableMetrics metrics) {
        List<Candidate<K>> deferredCandidates = new ArrayList<>();
        int scanBudget = configuration.scanBudgetPerTick();
        while (metrics.scansPerformed < scanBudget && !pending.isEmpty()) {
            Iterator<Map.Entry<K, Candidate<K>>> iterator =
                    pending.entrySet().iterator();
            Map.Entry<K, Candidate<K>> next = iterator.next();
            Candidate<K> candidate = next.getValue();
            iterator.remove();
            metrics.scansPerformed++;

            ProbeResult<V> result;
            try {
                result = Objects.requireNonNull(
                        probe.inspect(candidate),
                        "probe result"
                );
            } catch (RuntimeException | Error failure) {
                // Preserve unprocessed intent without allowing the failed key
                // to be retried repeatedly in the same tick.
                deferredCandidates.add(candidate);
                appendDeferred(deferredCandidates);
                throw failure;
            }

            switch (result.outcome()) {
                case HIT -> {
                    cacheHit(candidate, result.value(), metrics);
                    metrics.hits++;
                }
                case MISS -> {
                    cached.remove(candidate.key());
                    metrics.misses++;
                }
                case DEFER -> {
                    deferredCandidates.add(candidate);
                    metrics.deferred++;
                }
            }
        }
        appendDeferred(deferredCandidates);
    }

    private void appendDeferred(List<Candidate<K>> deferredCandidates) {
        for (Candidate<K> candidate : deferredCandidates) {
            if (pending.size()
                    >= configuration.maxPendingCandidates()) {
                break;
            }
            pending.putIfAbsent(candidate.key(), candidate);
        }
    }

    private void cacheHit(
            Candidate<K> candidate,
            V value,
            MutableMetrics metrics
    ) {
        MutableCacheEntry<K, V> existing = cached.get(candidate.key());
        if (existing != null) {
            existing.position = candidate.position();
            existing.value = value;
            existing.scannedTick = scopeTick;
            return;
        }

        cached.put(
                candidate.key(),
                new MutableCacheEntry<>(
                        candidate.key(),
                        candidate.position(),
                        value,
                        scopeTick
                )
        );
    }

    private void prunePendingByDistance(MutableMetrics metrics) {
        Iterator<Candidate<K>> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            if (!withinDistance(iterator.next().position())) {
                iterator.remove();
                metrics.pendingPrunedByDistance++;
            }
        }
    }

    private void pruneCache(MutableMetrics metrics) {
        Iterator<MutableCacheEntry<K, V>> iterator =
                cached.values().iterator();
        while (iterator.hasNext()) {
            MutableCacheEntry<K, V> entry = iterator.next();
            if (!withinDistance(entry.position)) {
                iterator.remove();
                metrics.cachePrunedByDistance++;
            } else if (scopeTick - entry.scannedTick
                    > configuration.maxCacheAgeTicks()) {
                iterator.remove();
                metrics.cacheExpired++;
            }
        }
    }

    private void trimCacheToCapacity(MutableMetrics metrics) {
        int maximum = configuration.maxCacheEntries();
        if (cached.size() <= maximum) {
            return;
        }
        List<MutableCacheEntry<K, V>> nearest =
                new ArrayList<>(cached.values());
        nearest.sort(
                java.util.Comparator
                        .comparingDouble(
                                (MutableCacheEntry<K, V> entry) ->
                                        distanceSquared(entry.position)
                        )
                        .thenComparing(
                                java.util.Comparator.comparingLong(
                                        (MutableCacheEntry<K, V> entry) ->
                                                entry.scannedTick
                                ).reversed()
                        )
        );
        int evictions = cached.size() - maximum;
        cached.clear();
        for (int index = 0; index < maximum; index++) {
            MutableCacheEntry<K, V> entry = nearest.get(index);
            cached.put(entry.key, entry);
        }
        metrics.cacheCapacityEvictions += evictions;
    }

    /**
     * Builds one immutable nearest-first view after mutation. Render
     * extraction can safely reuse this list for every frame between client
     * ticks instead of allocating and sorting the entire cache at frame rate.
     */
    private void rebuildCachedSnapshot() {
        List<MutableCacheEntry<K, V>> nearest =
                new ArrayList<>(cached.values());
        nearest.sort(
                java.util.Comparator.comparingDouble(
                        entry -> distanceSquared(entry.position)
                )
        );
        List<CacheEntry<K, V>> snapshot =
                new ArrayList<>(nearest.size());
        for (MutableCacheEntry<K, V> entry : nearest) {
            snapshot.add(entry.snapshot());
        }
        cachedSnapshot = List.copyOf(snapshot);
    }

    private boolean withinDistance(Point position) {
        return distanceSquared(position)
                <= configuration.maxDistanceSquared();
    }

    private double distanceSquared(Point position) {
        double x = position.x() - observer.x();
        double y = position.y() - observer.y();
        double z = position.z() - observer.z();
        return x * x + y * y + z * z;
    }

    private static boolean sameScope(Scope left, Scope right) {
        return left != null
                && left.sessionIdentity() == right.sessionIdentity()
                && left.worldIdentity() == right.worldIdentity()
                && left.dimensionKey().equals(right.dimensionKey());
    }

    public record Configuration(
            int scanBudgetPerTick,
            int candidateAdmissionBudgetPerTick,
            int maxPendingCandidates,
            int maxCacheEntries,
            double maxDistanceBlocks,
            long maxCacheAgeTicks
    ) {
        public Configuration {
            if (scanBudgetPerTick < 1
                    || scanBudgetPerTick > HARD_MAX_SCAN_BUDGET) {
                throw new IllegalArgumentException(
                        "scanBudgetPerTick must be in [1, "
                                + HARD_MAX_SCAN_BUDGET
                                + "]"
                );
            }
            if (candidateAdmissionBudgetPerTick < 1
                    || candidateAdmissionBudgetPerTick
                    > HARD_MAX_ADMISSION_BUDGET) {
                throw new IllegalArgumentException(
                        "candidateAdmissionBudgetPerTick must be in [1, "
                                + HARD_MAX_ADMISSION_BUDGET
                                + "]"
                );
            }
            if (maxPendingCandidates < scanBudgetPerTick) {
                throw new IllegalArgumentException(
                        "maxPendingCandidates must cover one scan budget"
                );
            }
            if (maxPendingCandidates > HARD_MAX_PENDING_CANDIDATES) {
                throw new IllegalArgumentException(
                        "maxPendingCandidates must be at most "
                                + HARD_MAX_PENDING_CANDIDATES
                );
            }
            if (maxCacheEntries < 1
                    || maxCacheEntries > HARD_MAX_CACHE_ENTRIES) {
                throw new IllegalArgumentException(
                        "maxCacheEntries must be in [1, "
                                + HARD_MAX_CACHE_ENTRIES
                                + "]"
                );
            }
            if (!Double.isFinite(maxDistanceBlocks)
                    || maxDistanceBlocks <= 0.0
                    || maxDistanceBlocks > HARD_MAX_DISTANCE_BLOCKS) {
                throw new IllegalArgumentException(
                        "maxDistanceBlocks must be finite, positive, and at most "
                                + HARD_MAX_DISTANCE_BLOCKS
                );
            }
            if (maxCacheAgeTicks < 1L
                    || maxCacheAgeTicks > HARD_MAX_CACHE_AGE_TICKS) {
                throw new IllegalArgumentException(
                        "maxCacheAgeTicks must be in [1, "
                                + HARD_MAX_CACHE_AGE_TICKS
                                + "]"
                );
            }
        }

        public static Configuration defaults() {
            return new Configuration(
                    128,
                    1_024,
                    8_192,
                    4_096,
                    128.0,
                    600L
            );
        }

        public double maxDistanceSquared() {
            return maxDistanceBlocks * maxDistanceBlocks;
        }
    }

    public record Scope(
            Object sessionIdentity,
            Object worldIdentity,
            Object dimensionKey
    ) {
        public Scope {
            Objects.requireNonNull(sessionIdentity, "sessionIdentity");
            Objects.requireNonNull(worldIdentity, "worldIdentity");
            Objects.requireNonNull(dimensionKey, "dimensionKey");
        }
    }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)) {
                throw new IllegalArgumentException(
                        "point coordinates must be finite"
                );
            }
        }

        public static Point blockCenter(int x, int y, int z) {
            return new Point(x + 0.5, y + 0.5, z + 0.5);
        }
    }

    public record Candidate<K>(K key, Point position) {
        public Candidate {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(position, "position");
        }
    }

    @FunctionalInterface
    public interface Probe<K, V> {
        ProbeResult<V> inspect(Candidate<K> candidate);
    }

    public enum ProbeOutcome {
        HIT,
        MISS,
        DEFER
    }

    public record ProbeResult<V>(ProbeOutcome outcome, V value) {
        public ProbeResult {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == ProbeOutcome.HIT) {
                Objects.requireNonNull(value, "value");
            } else if (value != null) {
                throw new IllegalArgumentException(
                        "only HIT may contain a value"
                );
            }
        }

        public static <V> ProbeResult<V> hit(V value) {
            return new ProbeResult<>(ProbeOutcome.HIT, value);
        }

        public static <V> ProbeResult<V> miss() {
            return new ProbeResult<>(ProbeOutcome.MISS, null);
        }

        public static <V> ProbeResult<V> defer() {
            return new ProbeResult<>(ProbeOutcome.DEFER, null);
        }
    }

    public record CacheEntry<K, V>(
            K key,
            Point position,
            V value,
            long scannedTick
    ) {
    }

    public record TickResult<K, V>(
            boolean scopeReset,
            long scopeTick,
            int candidatesExamined,
            int candidatesAdmitted,
            int candidatesUpdated,
            int candidatesRejectedByDistance,
            int pendingPrunedByDistance,
            int cachePrunedByDistance,
            int cacheExpired,
            int pendingCapacityEvictions,
            int cacheCapacityEvictions,
            int scansPerformed,
            int hits,
            int misses,
            int deferred,
            int scopeEntriesCleared,
            int pendingCandidates,
            int cacheSize,
            int remainingScanBudget,
            List<CacheEntry<K, V>> entries
    ) {
        public TickResult {
            entries = List.copyOf(entries);
        }
    }

    public record Snapshot<K, V>(
            boolean scopePresent,
            long scopeTick,
            int pendingCandidates,
            List<CacheEntry<K, V>> entries
    ) {
        public Snapshot {
            entries = List.copyOf(entries);
        }
    }

    private static final class MutableCacheEntry<K, V> {
        private final K key;
        private Point position;
        private V value;
        private long scannedTick;

        private MutableCacheEntry(
                K key,
                Point position,
                V value,
                long scannedTick
        ) {
            this.key = key;
            this.position = position;
            this.value = value;
            this.scannedTick = scannedTick;
        }

        private CacheEntry<K, V> snapshot() {
            return new CacheEntry<>(key, position, value, scannedTick);
        }
    }

    private static final class MutableMetrics {
        private int candidatesExamined;
        private int candidatesAdmitted;
        private int candidatesUpdated;
        private int candidatesRejectedByDistance;
        private int pendingPrunedByDistance;
        private int cachePrunedByDistance;
        private int cacheExpired;
        private int pendingCapacityEvictions;
        private int cacheCapacityEvictions;
        private int scansPerformed;
        private int hits;
        private int misses;
        private int deferred;
        private int scopeEntriesCleared;
    }
}
