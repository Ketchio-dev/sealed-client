package dev.sealedclient.module.world;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.IntegerSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Incrementally discovers clusters of storage block entities in loaded chunks.
 *
 * <p>The scan cursor is retained between ticks. Entering a chunk and inspecting
 * one block entity each consume one unit of the configured budget, so a dense
 * base cannot turn a single client tick into an unbounded world scan. Findings
 * are retained only for the current level and dimension and never leave the
 * client process.</p>
 */
public final class StashFinderModule extends Module implements TickableModule {
    public static final String ID = "stash_finder";

    private final IntegerSetting range = addSetting(new IntegerSetting(
            "range",
            "Range",
            "Horizontal block radius covered by each incremental scan.",
            256,
            32,
            512,
            16
    ));
    private final IntegerSetting scanBudget = addSetting(new IntegerSetting(
            "scan_budget",
            "Scan Budget",
            "Maximum chunk entries and block entities inspected per client tick.",
            128,
            16,
            2_048,
            16
    ));
    private final IntegerSetting minimumContainers = addSetting(new IntegerSetting(
            "minimum_containers",
            "Minimum Containers",
            "Minimum nearby storage blocks required to report a stash.",
            6,
            2,
            128,
            1
    ));
    private final IntegerSetting maximumEntries = addSetting(new IntegerSetting(
            "maximum_entries",
            "Maximum Entries",
            "Maximum number of stash clusters retained for the current session.",
            128,
            8,
            1_024,
            8
    ));

    private final Map<Long, ChunkFinding> sweepFindings = new HashMap<>();
    private final Map<Long, StoredStash> sessionStashes = new LinkedHashMap<>();

    private ClientLevel activeLevel;
    private ResourceLocation activeDimension;
    private BlockPos sweepOrigin = BlockPos.ZERO;
    private Iterator<BlockEntity> activeBlockEntities = Collections.emptyIterator();
    private long activeChunk;
    private long moduleTick;
    private long nextStashId = 1L;
    private int sweepRange;
    private int sweepChunkRadius;
    private int sweepSide;
    private int sweepChunkCursor;
    private int sweepChunkCount;
    private int publishedMinimum;
    private int publishedMaximum;
    private List<StashSnapshot> publishedSnapshot = List.of();

    public StashFinderModule() {
        super(
                ID,
                "Stash Finder",
                "Finds clusters of storage blocks with a bounded incremental scan.",
                Category.UTILITY,
                false,
                ModuleRisk.PASSIVE
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            clearSession();
            return;
        }

        ClientLevel level = minecraft.level;
        ResourceLocation dimension = level.dimension().location();
        if (level != activeLevel || !Objects.equals(dimension, activeDimension)) {
            beginSession(level, dimension, minecraft.player.blockPosition());
        }

        moduleTick++;
        if (sweepRange != range.get()
                || horizontalDistanceSquared(sweepOrigin, minecraft.player.blockPosition())
                > (long) sweepRange * sweepRange) {
            beginSweep(minecraft.player.blockPosition());
        }

        enforceMaximumEntries();
        if (publishedMinimum != minimumContainers.get()
                || publishedMaximum != maximumEntries.get()) {
            rebuildSnapshot();
        }

        int remainingBudget = scanBudget.get();
        while (remainingBudget > 0) {
            if (activeBlockEntities != null) {
                try {
                    while (remainingBudget > 0 && activeBlockEntities.hasNext()) {
                        inspect(activeBlockEntities.next());
                        remainingBudget--;
                    }
                    if (activeBlockEntities.hasNext()) {
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // A chunk can unload or replace its block-entity map between
                    // ticks. Discard that iterator and continue the finite sweep.
                }
                activeBlockEntities = null;
            }

            if (sweepChunkCursor >= sweepChunkCount) {
                publishSweep();
                beginSweep(minecraft.player.blockPosition());
                if (remainingBudget <= 0) {
                    return;
                }
            }

            openNextChunk(level);
            remainingBudget--;
        }
    }

    /**
     * Returns a stable, immutable view of stash clusters found in this session.
     */
    public List<StashSnapshot> snapshot() {
        return publishedSnapshot;
    }

    public ScanProgress scanProgress() {
        return new ScanProgress(
                sweepChunkCursor,
                sweepChunkCount,
                sweepOrigin.getX(),
                sweepOrigin.getY(),
                sweepOrigin.getZ()
        );
    }

    public int range() {
        return range.get();
    }

    public int scanBudget() {
        return scanBudget.get();
    }

    public int minimumContainers() {
        return minimumContainers.get();
    }

    public int maximumEntries() {
        return maximumEntries.get();
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        clearSession();
    }

    private void beginSession(
            ClientLevel level,
            ResourceLocation dimension,
            BlockPos origin
    ) {
        clearSession();
        activeLevel = level;
        activeDimension = dimension;
        beginSweep(origin);
    }

    private void beginSweep(BlockPos origin) {
        sweepOrigin = origin.immutable();
        sweepRange = range.get();
        sweepChunkRadius = (sweepRange + 15) / 16;
        sweepSide = sweepChunkRadius * 2 + 1;
        sweepChunkCount = sweepSide * sweepSide;
        sweepChunkCursor = 0;
        activeBlockEntities = null;
        activeChunk = 0L;
        sweepFindings.clear();
    }

    private void clearSession() {
        activeLevel = null;
        activeDimension = null;
        sweepOrigin = BlockPos.ZERO;
        activeBlockEntities = null;
        activeChunk = 0L;
        moduleTick = 0L;
        nextStashId = 1L;
        sweepRange = 0;
        sweepChunkRadius = 0;
        sweepSide = 0;
        sweepChunkCursor = 0;
        sweepChunkCount = 0;
        publishedMinimum = 0;
        publishedMaximum = 0;
        sweepFindings.clear();
        sessionStashes.clear();
        publishedSnapshot = List.of();
    }

    private void openNextChunk(ClientLevel level) {
        int index = sweepChunkCursor++;
        int offsetX = index % sweepSide - sweepChunkRadius;
        int offsetZ = index / sweepSide - sweepChunkRadius;
        int chunkX = (sweepOrigin.getX() >> 4) + offsetX;
        int chunkZ = (sweepOrigin.getZ() >> 4) + offsetZ;
        if (!level.hasChunk(chunkX, chunkZ)) {
            activeBlockEntities = null;
            return;
        }

        activeChunk = ChunkPos.asLong(chunkX, chunkZ);
        activeBlockEntities = level.getChunk(chunkX, chunkZ)
                .getBlockEntities()
                .values()
                .iterator();
    }

    private void inspect(BlockEntity blockEntity) {
        ContainerKind kind = ContainerKind.classify(blockEntity.getType());
        if (kind == null) {
            return;
        }

        BlockPos position = blockEntity.getBlockPos();
        long dx = position.getX() - sweepOrigin.getX();
        long dz = position.getZ() - sweepOrigin.getZ();
        if (dx * dx + dz * dz > (long) sweepRange * sweepRange) {
            return;
        }

        sweepFindings.computeIfAbsent(activeChunk, ignored -> new ChunkFinding())
                .add(position, kind);
    }

    private void publishSweep() {
        List<ClusterCandidate> candidates = clusterFindings();
        candidates.sort(Comparator.comparingInt(ClusterCandidate::containerCount).reversed());
        for (ClusterCandidate candidate : candidates) {
            if (candidate.containerCount() < minimumContainers.get()) {
                continue;
            }
            mergeCandidate(candidate);
        }
        enforceMaximumEntries();
        rebuildSnapshot();
    }

    private List<ClusterCandidate> clusterFindings() {
        List<ClusterCandidate> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> pending = new ArrayDeque<>();

        for (long seed : sweepFindings.keySet()) {
            if (!visited.add(seed)) {
                continue;
            }

            ClusterAccumulator cluster = new ClusterAccumulator();
            pending.add(seed);
            while (!pending.isEmpty()) {
                long packedChunk = pending.removeFirst();
                int chunkX = ChunkPos.getX(packedChunk);
                int chunkZ = ChunkPos.getZ(packedChunk);
                cluster.add(chunkX, chunkZ, sweepFindings.get(packedChunk));

                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                        if (offsetX == 0 && offsetZ == 0) {
                            continue;
                        }
                        long neighbour = ChunkPos.asLong(chunkX + offsetX, chunkZ + offsetZ);
                        if (sweepFindings.containsKey(neighbour) && visited.add(neighbour)) {
                            pending.addLast(neighbour);
                        }
                    }
                }
            }
            result.add(cluster.finish());
        }
        return result;
    }

    private void mergeCandidate(ClusterCandidate candidate) {
        Long matchingId = null;
        long bestDistanceSquared = Long.MAX_VALUE;
        for (Map.Entry<Long, StoredStash> entry : sessionStashes.entrySet()) {
            StoredStash stored = entry.getValue();
            if (!stored.touches(candidate)) {
                continue;
            }
            long distanceSquared = stored.distanceSquared(candidate);
            if (distanceSquared < bestDistanceSquared) {
                matchingId = entry.getKey();
                bestDistanceSquared = distanceSquared;
            }
        }

        if (matchingId == null) {
            StoredStash stored = StoredStash.from(
                    nextStashId++,
                    activeDimension.toString(),
                    candidate,
                    moduleTick
            );
            sessionStashes.put(stored.id, stored);
            return;
        }

        StoredStash existing = sessionStashes.remove(matchingId);
        sessionStashes.put(matchingId, existing.merge(candidate, moduleTick));
    }

    private void enforceMaximumEntries() {
        while (sessionStashes.size() > maximumEntries.get()) {
            Iterator<Long> iterator = sessionStashes.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private void rebuildSnapshot() {
        List<StashSnapshot> refreshed = new ArrayList<>(sessionStashes.size());
        for (StoredStash stash : sessionStashes.values()) {
            if (stash.containerCount < minimumContainers.get()) {
                continue;
            }
            refreshed.add(stash.snapshot());
        }
        refreshed.sort(
                Comparator.comparingInt(StashSnapshot::containerCount)
                        .reversed()
                        .thenComparing(
                                Comparator.comparingLong(StashSnapshot::lastObservedTick)
                                        .reversed()
                        )
        );
        publishedSnapshot = List.copyOf(refreshed);
        publishedMinimum = minimumContainers.get();
        publishedMaximum = maximumEntries.get();
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = first.getX() - second.getX();
        long dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
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
        CRAFTER;

        private static ContainerKind classify(BlockEntityType<?> type) {
            if (type == BlockEntityType.CHEST) {
                return CHEST;
            }
            if (type == BlockEntityType.TRAPPED_CHEST) {
                return TRAPPED_CHEST;
            }
            if (type == BlockEntityType.ENDER_CHEST) {
                return ENDER_CHEST;
            }
            if (type == BlockEntityType.BARREL) {
                return BARREL;
            }
            if (type == BlockEntityType.SHULKER_BOX) {
                return SHULKER_BOX;
            }
            if (type == BlockEntityType.HOPPER) {
                return HOPPER;
            }
            if (type == BlockEntityType.DISPENSER) {
                return DISPENSER;
            }
            if (type == BlockEntityType.DROPPER) {
                return DROPPER;
            }
            if (type == BlockEntityType.FURNACE) {
                return FURNACE;
            }
            if (type == BlockEntityType.BLAST_FURNACE) {
                return BLAST_FURNACE;
            }
            if (type == BlockEntityType.SMOKER) {
                return SMOKER;
            }
            if (type == BlockEntityType.BREWING_STAND) {
                return BREWING_STAND;
            }
            if (type == BlockEntityType.CRAFTER) {
                return CRAFTER;
            }
            return null;
        }
    }

    public record StashSnapshot(
            long id,
            String dimension,
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
            Objects.requireNonNull(dimension, "dimension");
            containerCounts = Map.copyOf(containerCounts);
        }
    }

    public record ScanProgress(
            int scannedChunks,
            int totalChunks,
            int originX,
            int originY,
            int originZ
    ) {
    }

    private static final class ChunkFinding {
        private final EnumMap<ContainerKind, Integer> counts =
                new EnumMap<>(ContainerKind.class);
        private int containerCount;
        private long xSum;
        private long ySum;
        private long zSum;

        private void add(BlockPos position, ContainerKind kind) {
            containerCount++;
            xSum += position.getX();
            ySum += position.getY();
            zSum += position.getZ();
            counts.merge(kind, 1, Integer::sum);
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
            containerCount += finding.containerCount;
            xSum += finding.xSum;
            ySum += finding.ySum;
            zSum += finding.zSum;
            finding.counts.forEach(
                    (kind, count) -> counts.merge(kind, count, Integer::sum)
            );
        }

        private ClusterCandidate finish() {
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
                ClusterCandidate candidate,
                long observedTick
        ) {
            return new StoredStash(
                    id,
                    dimension,
                    candidate.centerX(),
                    candidate.centerY(),
                    candidate.centerZ(),
                    candidate.minimumChunkX(),
                    candidate.maximumChunkX(),
                    candidate.minimumChunkZ(),
                    candidate.maximumChunkZ(),
                    candidate.containerCount(),
                    candidate.containerCounts(),
                    observedTick,
                    observedTick
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

        private StoredStash merge(ClusterCandidate candidate, long observedTick) {
            if (candidate.containerCount() < containerCount) {
                return new StoredStash(
                        id,
                        dimension,
                        centerX,
                        centerY,
                        centerZ,
                        Math.min(minimumChunkX, candidate.minimumChunkX()),
                        Math.max(maximumChunkX, candidate.maximumChunkX()),
                        Math.min(minimumChunkZ, candidate.minimumChunkZ()),
                        Math.max(maximumChunkZ, candidate.maximumChunkZ()),
                        containerCount,
                        containerCounts,
                        firstObservedTick,
                        observedTick
                );
            }
            return new StoredStash(
                    id,
                    dimension,
                    candidate.centerX(),
                    candidate.centerY(),
                    candidate.centerZ(),
                    Math.min(minimumChunkX, candidate.minimumChunkX()),
                    Math.max(maximumChunkX, candidate.maximumChunkX()),
                    Math.min(minimumChunkZ, candidate.minimumChunkZ()),
                    Math.max(maximumChunkZ, candidate.maximumChunkZ()),
                    candidate.containerCount(),
                    candidate.containerCounts(),
                    firstObservedTick,
                    observedTick
            );
        }

        private StashSnapshot snapshot() {
            return new StashSnapshot(
                    id,
                    dimension,
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
}
