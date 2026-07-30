package dev.sealedclient.module.world;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.IntegerSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Incrementally finds connected nether-portal blocks and exposes their
 * corresponding Overworld/Nether coordinates.
 *
 * <p>Observations are retained while the client remains on the same server
 * connection, including ordinary dimension changes. They are deliberately not
 * written to disk, and are cleared on disconnect or when the module is
 * disabled.</p>
 */
public final class PortalCoordsModule extends Module implements TickableModule {
    public static final String ID = "portal_coords";
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    private static final int MAX_TRACKED_PORTAL_BLOCKS = 32_768;

    private final IntegerSetting scanRange = addSetting(new IntegerSetting(
            "scan_range",
            "Scan Range",
            "Horizontal block radius searched around the player.",
            128,
            16,
            512,
            16
    ));
    private final IntegerSetting scanBudget = addSetting(new IntegerSetting(
            "scan_budget",
            "Scan Budget",
            "Maximum number of block positions inspected each tick.",
            4096,
            256,
            32768,
            256
    ));
    private final IntegerSetting maximumEntries = addSetting(new IntegerSetting(
            "maximum_entries",
            "Maximum Entries",
            "Maximum number of connected portal observations retained this session.",
            128,
            8,
            512,
            8
    ));

    private final Map<PortalBlockKey, Long> blockOwners = new HashMap<>();
    private final Map<Long, MutablePortalCluster> clusters = new LinkedHashMap<>();

    private ClientPacketListener activeConnection;
    private ClientLevel scanLevel;
    private ResourceLocation scanDimension;
    private BlockPos scanOrigin = BlockPos.ZERO;
    private int activeRange;
    private int minimumY;
    private int scanHeight;
    private long scanCursor;
    private long moduleTick;
    private long nextClusterId = 1L;

    public PortalCoordsModule() {
        super(
                ID,
                "Portal Coords",
                "Finds nether portals and calculates their linked-dimension coordinates.",
                Category.UTILITY,
                false,
                ModuleRisk.PASSIVE
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft == null || minecraft.getConnection() == null) {
            clearSession();
            return;
        }

        ClientPacketListener connection = minecraft.getConnection();
        if (connection != activeConnection) {
            clearSession();
            activeConnection = connection;
        }
        if (minecraft.level == null || minecraft.player == null) {
            // The same connection can briefly have no level while changing
            // dimensions. Preserve session observations across that transition.
            resetScanCursor();
            return;
        }

        moduleTick++;
        enforceLimits();

        ClientLevel level = minecraft.level;
        ResourceLocation dimension = level.dimension().location();
        BlockPos playerPosition = minecraft.player.blockPosition();
        int range = scanRange.get();
        boolean restartScan = level != scanLevel
                || !Objects.equals(dimension, scanDimension)
                || activeRange != range
                || horizontalDistance(scanOrigin, playerPosition) > Math.max(8, range / 4)
                || Math.abs(scanOrigin.getY() - playerPosition.getY()) > 16;
        if (restartScan) {
            scanLevel = level;
            scanDimension = dimension;
            scanOrigin = playerPosition;
            activeRange = range;
            minimumY = level.getMinY();
            scanHeight = Math.max(1, level.getHeight());
            scanCursor = 0L;
        }

        scan(level, dimension.toString(), range, scanBudget.get());
    }

    /**
     * Returns immutable, insertion-ordered portal cluster snapshots.
     */
    public List<PortalSnapshot> snapshot() {
        List<PortalSnapshot> result = new ArrayList<>(clusters.size());
        for (MutablePortalCluster cluster : clusters.values()) {
            result.add(cluster.snapshot());
        }
        return List.copyOf(result);
    }

    /**
     * Returns coordinate conversions for all portal clusters in dimensions
     * where the vanilla 8:1 Overworld/Nether rule applies.
     */
    public List<CoordinateConversionSnapshot> conversionSnapshot() {
        List<CoordinateConversionSnapshot> result = new ArrayList<>(clusters.size());
        for (MutablePortalCluster cluster : clusters.values()) {
            PortalSnapshot portal = cluster.snapshot();
            convert(
                    portal.dimension(),
                    portal.centerX(),
                    portal.centerY(),
                    portal.centerZ()
            ).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /**
     * Calculates a vanilla Overworld/Nether coordinate conversion without
     * mutating the module's observations.
     */
    public static Optional<CoordinateConversionSnapshot> convert(
            String sourceDimension,
            double sourceX,
            double sourceY,
            double sourceZ
    ) {
        if (OVERWORLD.equals(sourceDimension)) {
            return Optional.of(new CoordinateConversionSnapshot(
                    OVERWORLD,
                    NETHER,
                    sourceX,
                    sourceY,
                    sourceZ,
                    sourceX / 8.0,
                    sourceY,
                    sourceZ / 8.0
            ));
        }
        if (NETHER.equals(sourceDimension)) {
            return Optional.of(new CoordinateConversionSnapshot(
                    NETHER,
                    OVERWORLD,
                    sourceX,
                    sourceY,
                    sourceZ,
                    sourceX * 8.0,
                    sourceY,
                    sourceZ * 8.0
            ));
        }
        return Optional.empty();
    }

    public int scanRange() {
        return scanRange.get();
    }

    public int scanBudget() {
        return scanBudget.get();
    }

    public int maximumEntries() {
        return maximumEntries.get();
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        clearSession();
    }

    private void scan(
            ClientLevel level,
            String dimension,
            int range,
            int budget
    ) {
        int side = range * 2 + 1;
        long layerSize = (long) side * side;
        long volume = layerSize * scanHeight;
        if (volume <= 0L) {
            return;
        }

        long rangeSquared = (long) range * range;
        BlockPos.MutableBlockPos mutablePosition = new BlockPos.MutableBlockPos();
        for (int inspected = 0; inspected < budget; inspected++) {
            long index = scanCursor++ % volume;
            int yOffset = (int) (index % scanHeight);
            long horizontalIndex = index / scanHeight;
            int xOffset = (int) (horizontalIndex % side) - range;
            int zOffset = (int) (horizontalIndex / side) - range;
            if ((long) xOffset * xOffset + (long) zOffset * zOffset > rangeSquared) {
                continue;
            }

            mutablePosition.set(
                    scanOrigin.getX() + xOffset,
                    minimumY + yOffset,
                    scanOrigin.getZ() + zOffset
            );
            if (!level.hasChunk(
                    mutablePosition.getX() >> 4,
                    mutablePosition.getZ() >> 4
            )) {
                continue;
            }

            if (level.getBlockState(mutablePosition).is(Blocks.NETHER_PORTAL)) {
                observePortalBlock(new PortalBlockKey(
                        dimension,
                        mutablePosition.asLong()
                ));
            }
        }
    }

    private void observePortalBlock(PortalBlockKey key) {
        Long existingOwner = blockOwners.get(key);
        if (existingOwner != null) {
            MutablePortalCluster cluster = clusters.get(existingOwner);
            if (cluster != null) {
                cluster.lastObservedTick = moduleTick;
            } else {
                blockOwners.remove(key);
            }
            return;
        }
        if (blockOwners.size() >= MAX_TRACKED_PORTAL_BLOCKS) {
            return;
        }

        Set<Long> adjacentOwners = new LinkedHashSet<>();
        BlockPos position = BlockPos.of(key.packedPosition());
        for (Direction direction : Direction.values()) {
            PortalBlockKey neighbour = new PortalBlockKey(
                    key.dimension(),
                    position.relative(direction).asLong()
            );
            Long owner = blockOwners.get(neighbour);
            if (owner != null && clusters.containsKey(owner)) {
                adjacentOwners.add(owner);
            }
        }

        MutablePortalCluster destination;
        if (adjacentOwners.isEmpty()) {
            enforceLimits();
            if (clusters.size() >= maximumEntries.get()) {
                removeOldestCluster();
            }
            long id = nextClusterId++;
            destination = new MutablePortalCluster(id, key.dimension(), moduleTick);
            clusters.put(id, destination);
        } else {
            long owner = adjacentOwners.iterator().next();
            destination = clusters.get(owner);
        }

        destination.add(key, moduleTick);
        blockOwners.put(key, destination.id);

        for (Long owner : adjacentOwners) {
            if (owner != destination.id) {
                mergeCluster(destination, owner);
            }
        }
        enforceLimits();
    }

    private void mergeCluster(MutablePortalCluster destination, long sourceId) {
        MutablePortalCluster source = clusters.remove(sourceId);
        if (source == null || source == destination) {
            return;
        }
        destination.firstObservedTick = Math.min(
                destination.firstObservedTick,
                source.firstObservedTick
        );
        for (PortalBlockKey key : source.blocks) {
            destination.add(key, Math.max(destination.lastObservedTick, source.lastObservedTick));
            blockOwners.put(key, destination.id);
        }
    }

    private void enforceLimits() {
        while (clusters.size() > maximumEntries.get()) {
            removeOldestCluster();
        }
        while (blockOwners.size() > MAX_TRACKED_PORTAL_BLOCKS && !clusters.isEmpty()) {
            removeOldestCluster();
        }
    }

    private void removeOldestCluster() {
        Iterator<Map.Entry<Long, MutablePortalCluster>> iterator =
                clusters.entrySet().iterator();
        if (!iterator.hasNext()) {
            return;
        }
        MutablePortalCluster removed = iterator.next().getValue();
        iterator.remove();
        for (PortalBlockKey key : removed.blocks) {
            blockOwners.remove(key);
        }
    }

    private void resetScanCursor() {
        scanLevel = null;
        scanDimension = null;
        scanCursor = 0L;
    }

    private void clearSession() {
        activeConnection = null;
        resetScanCursor();
        scanOrigin = BlockPos.ZERO;
        activeRange = 0;
        minimumY = 0;
        scanHeight = 0;
        moduleTick = 0L;
        nextClusterId = 1L;
        blockOwners.clear();
        clusters.clear();
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.max(
                Math.abs(first.getX() - second.getX()),
                Math.abs(first.getZ() - second.getZ())
        );
    }

    public record PortalSnapshot(
            long id,
            String dimension,
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ,
            double centerX,
            double centerY,
            double centerZ,
            int portalBlockCount,
            long firstObservedTick,
            long lastObservedTick
    ) {
    }

    public record CoordinateConversionSnapshot(
            String sourceDimension,
            String targetDimension,
            double sourceX,
            double sourceY,
            double sourceZ,
            double targetX,
            double targetY,
            double targetZ
    ) {
    }

    private record PortalBlockKey(String dimension, long packedPosition) {
        private PortalBlockKey {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    private static final class MutablePortalCluster {
        private final long id;
        private final String dimension;
        private final Set<PortalBlockKey> blocks = new LinkedHashSet<>();
        private long firstObservedTick;
        private long lastObservedTick;
        private int minimumX;
        private int minimumY;
        private int minimumZ;
        private int maximumX;
        private int maximumY;
        private int maximumZ;

        private MutablePortalCluster(long id, String dimension, long observedAtTick) {
            this.id = id;
            this.dimension = dimension;
            this.firstObservedTick = observedAtTick;
            this.lastObservedTick = observedAtTick;
        }

        private void add(PortalBlockKey key, long observedAtTick) {
            if (!blocks.add(key)) {
                lastObservedTick = Math.max(lastObservedTick, observedAtTick);
                return;
            }

            BlockPos position = BlockPos.of(key.packedPosition());
            if (blocks.size() == 1) {
                minimumX = maximumX = position.getX();
                minimumY = maximumY = position.getY();
                minimumZ = maximumZ = position.getZ();
            } else {
                minimumX = Math.min(minimumX, position.getX());
                minimumY = Math.min(minimumY, position.getY());
                minimumZ = Math.min(minimumZ, position.getZ());
                maximumX = Math.max(maximumX, position.getX());
                maximumY = Math.max(maximumY, position.getY());
                maximumZ = Math.max(maximumZ, position.getZ());
            }
            lastObservedTick = Math.max(lastObservedTick, observedAtTick);
        }

        private PortalSnapshot snapshot() {
            return new PortalSnapshot(
                    id,
                    dimension,
                    minimumX,
                    minimumY,
                    minimumZ,
                    maximumX,
                    maximumY,
                    maximumZ,
                    (minimumX + maximumX + 1.0) / 2.0,
                    (minimumY + maximumY + 1.0) / 2.0,
                    (minimumZ + maximumZ + 1.0) / 2.0,
                    blocks.size(),
                    firstObservedTick,
                    lastObservedTick
            );
        }
    }
}
