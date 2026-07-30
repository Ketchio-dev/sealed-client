package dev.sealedclient.render;

import dev.sealedclient.module.visual.BlockESPModule;
import dev.sealedclient.module.visual.HoleESPModule;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Incrementally scans a player-centred volume. The cursor advances by a fixed
 * per-tick budget, so enabling search overlays cannot turn rendering into an
 * unbounded cubic world walk.
 */
final class BoundedBlockScanner {
    private static final int VERTICAL_RANGE = 16;
    private static final int MAX_BLOCK_MATCHES = 8_192;
    private static final int MAX_HOLES = 2_048;

    private final Map<BlockPos, String> blockMatches = new LinkedHashMap<>();
    private final Map<BlockPos, HoleSafety> holes = new LinkedHashMap<>();
    private final Map<BlockPos, String> blockMatchesView =
            Collections.unmodifiableMap(blockMatches);
    private final Map<BlockPos, HoleSafety> holesView = Collections.unmodifiableMap(holes);
    private ResourceKey<Level> dimension;
    private BlockPos origin = BlockPos.ZERO;
    private Set<String> lastTargets = Set.of();
    private int lastRange;
    private long cursor;
    private int cleanupTicker;

    void tick(Minecraft minecraft, BlockESPModule blockEsp, HoleESPModule holeEsp) {
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }
        if (!blockEsp.isEnabled() && !holeEsp.isEnabled()) {
            clear();
            return;
        }

        int range = Math.max(
                blockEsp.isEnabled() ? blockEsp.range() : 0,
                holeEsp.isEnabled() ? holeEsp.range() : 0
        );
        Set<String> targets = blockEsp.isEnabled() ? blockEsp.targets() : Set.of();
        BlockPos playerOrigin = minecraft.player.blockPosition();
        boolean reset = dimension != minecraft.level.dimension()
                || lastRange != range
                || !lastTargets.equals(targets)
                || horizontalDistance(origin, playerOrigin) > Math.max(4, range / 3)
                || Math.abs(origin.getY() - playerOrigin.getY()) > 8;
        if (reset) {
            dimension = minecraft.level.dimension();
            origin = playerOrigin;
            lastRange = range;
            lastTargets = Set.copyOf(targets);
            cursor = 0L;
            blockMatches.clear();
            holes.clear();
        }

        int budget = blockEsp.isEnabled() ? blockEsp.scanBudget() : 1_024;
        int side = range * 2 + 1;
        int height = VERTICAL_RANGE * 2 + 1;
        long volume = (long) side * side * height;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int inspected = 0; inspected < budget && volume > 0L; inspected++) {
            long index = cursor++ % volume;
            int dx = (int) (index % side) - range;
            long yz = index / side;
            int dz = (int) (yz % side) - range;
            int dy = (int) (yz / side) - VERTICAL_RANGE;
            position.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
            if (!minecraft.level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) {
                continue;
            }

            BlockPos immutable = position.immutable();
            if (blockEsp.isEnabled()) {
                String id = BuiltInRegistries.BLOCK.getKey(
                        minecraft.level.getBlockState(position).getBlock()
                ).toString();
                if (targets.contains(id)) {
                    putBounded(blockMatches, immutable, id, MAX_BLOCK_MATCHES);
                } else {
                    blockMatches.remove(immutable);
                }
            }
            if (holeEsp.isEnabled() && Math.abs(dx) <= holeEsp.range()
                    && Math.abs(dz) <= holeEsp.range()) {
                HoleSafety safety = classifyHole(minecraft, position);
                if (safety == null) {
                    holes.remove(immutable);
                } else {
                    putBounded(holes, immutable, safety, MAX_HOLES);
                }
            }
        }

        if (++cleanupTicker >= 20) {
            cleanupTicker = 0;
            removeOutside(blockMatches, playerOrigin, blockEsp.range());
            removeOutside(holes, playerOrigin, holeEsp.range());
        }
    }

    Map<BlockPos, String> blockMatches() {
        return blockMatchesView;
    }

    Map<BlockPos, HoleSafety> holes() {
        return holesView;
    }

    void clear() {
        dimension = null;
        cursor = 0L;
        blockMatches.clear();
        holes.clear();
    }

    private static HoleSafety classifyHole(Minecraft minecraft, BlockPos position) {
        if (minecraft.level == null
                || !minecraft.level.getBlockState(position).getCollisionShape(
                        minecraft.level,
                        position
                ).isEmpty()
                || !minecraft.level.getBlockState(position.above()).getCollisionShape(
                        minecraft.level,
                        position.above()
                ).isEmpty()) {
            return null;
        }

        List<BlockPos> walls = List.of(
                position.below(),
                position.relative(Direction.NORTH),
                position.relative(Direction.SOUTH),
                position.relative(Direction.EAST),
                position.relative(Direction.WEST)
        );
        boolean hasObsidian = false;
        boolean unsafe = false;
        for (BlockPos wall : walls) {
            BlockState state = minecraft.level.getBlockState(wall);
            if (state.getCollisionShape(minecraft.level, wall).isEmpty()) {
                return null;
            }
            if (state.is(Blocks.BEDROCK)) {
                continue;
            }
            if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)) {
                hasObsidian = true;
            } else {
                unsafe = true;
            }
        }
        if (unsafe) {
            return HoleSafety.UNSAFE;
        }
        return hasObsidian ? HoleSafety.MIXED : HoleSafety.SAFE;
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.max(
                Math.abs(first.getX() - second.getX()),
                Math.abs(first.getZ() - second.getZ())
        );
    }

    private static <T> void removeOutside(Map<BlockPos, T> entries, BlockPos centre, int range) {
        if (range <= 0) {
            entries.clear();
            return;
        }
        long rangeSquared = (long) range * range;
        Iterator<BlockPos> iterator = entries.keySet().iterator();
        while (iterator.hasNext()) {
            BlockPos position = iterator.next();
            long dx = position.getX() - centre.getX();
            long dz = position.getZ() - centre.getZ();
            if (dx * dx + dz * dz > rangeSquared
                    || Math.abs(position.getY() - centre.getY()) > VERTICAL_RANGE + 2) {
                iterator.remove();
            }
        }
    }

    private static <K, V> void putBounded(Map<K, V> entries, K key, V value, int limit) {
        if (!entries.containsKey(key) && entries.size() >= limit) {
            Iterator<K> iterator = entries.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        entries.put(key, value);
    }

    enum HoleSafety {
        SAFE,
        MIXED,
        UNSAFE
    }
}
