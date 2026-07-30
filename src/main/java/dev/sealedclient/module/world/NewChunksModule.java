package dev.sealedclient.module.world;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Conservatively records chunks that become visible after the initial world
 * baseline has been established.
 *
 * <p>Without a chunk packet hook there is no reliable way to tell whether the
 * server generated a chunk recently. This module therefore reports only that a
 * loaded chunk is new to this client session. A fixed-size Bloom filter keeps
 * the session history bounded and can only turn a new observation into a
 * "possibly seen" result, avoiding false claims that an old chunk is new.</p>
 */
public final class NewChunksModule extends Module implements TickableModule {
    private static final int BLOOM_BIT_COUNT = 1 << 20;
    private static final int BLOOM_MASK = BLOOM_BIT_COUNT - 1;

    private final IntegerSetting scanRadius = addSetting(new IntegerSetting(
            "scan_radius",
            "Scan Radius",
            "Maximum radius, in chunks, checked around the player.",
            12,
            2,
            32,
            1
    ));
    private final IntegerSetting scanInterval = addSetting(new IntegerSetting(
            "scan_interval",
            "Scan Interval",
            "Ticks between loaded-chunk scans.",
            5,
            1,
            40,
            1
    ));
    private final IntegerSetting lifetime = addSetting(new IntegerSetting(
            "lifetime",
            "Lifetime",
            "Seconds that an observed chunk remains available to renderers.",
            300,
            10,
            3600,
            10
    ));
    private final IntegerSetting maximumEntries = addSetting(new IntegerSetting(
            "maximum_entries",
            "Maximum Entries",
            "Maximum number of recent chunk observations retained.",
            1024,
            64,
            8192,
            64
    ));
    private final ColorSetting color = addSetting(new ColorSetting(
            "color",
            "Color",
            "ARGB color used by world renderers.",
            0x9900D7FF
    ));
    private final BooleanSetting filled = addSetting(new BooleanSetting(
            "filled",
            "Filled",
            "Whether world renderers should draw a translucent fill.",
            true
    ));
    private final DoubleSetting lineWidth = addSetting(new DoubleSetting(
            "line_width",
            "Line Width",
            "Preferred outline width for world renderers.",
            1.5,
            0.5,
            5.0,
            0.5
    ));

    private final BitSet sessionSeenChunks = new BitSet(BLOOM_BIT_COUNT);
    private final Map<Long, MutableObservation> recentObservations = new LinkedHashMap<>();

    private ClientLevel activeLevel;
    private ResourceLocation activeDimension;
    private long moduleTick;
    private long nextScanTick;
    private boolean baselineEstablished;

    public NewChunksModule() {
        super(
                "new_chunks",
                "New Chunks",
                "Tracks chunks first observed after joining the current world.",
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
            beginSession(level, dimension);
        }

        moduleTick++;
        pruneExpired();
        enforceMaximumEntries();
        if (moduleTick < nextScanTick) {
            return;
        }
        nextScanTick = moduleTick + scanInterval.get();

        ChunkPos center = minecraft.player.chunkPosition();
        int radius = Math.min(
                scanRadius.get(),
                Math.max(2, minecraft.options.getEffectiveRenderDistance() + 1)
        );

        for (int chunkX = center.x - radius; chunkX <= center.x + radius; chunkX++) {
            for (int chunkZ = center.z - radius; chunkZ <= center.z + radius; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                observe(ChunkPos.asLong(chunkX, chunkZ));
            }
        }

        baselineEstablished = true;
    }

    /**
     * Returns a stable copy of all recent baseline and first-seen observations.
     */
    public List<ChunkSnapshot> snapshot() {
        List<ChunkSnapshot> result = new ArrayList<>(recentObservations.size());
        for (MutableObservation observation : recentObservations.values()) {
            result.add(observation.snapshot());
        }
        return List.copyOf(result);
    }

    /**
     * Returns only chunks first observed after the initial join baseline.
     */
    public List<ChunkSnapshot> freshlyObservedSnapshot() {
        List<ChunkSnapshot> result = new ArrayList<>();
        for (MutableObservation observation : recentObservations.values()) {
            if (observation.classification == Classification.FIRST_SEEN) {
                result.add(observation.snapshot());
            }
        }
        return List.copyOf(result);
    }

    public RenderSettings renderSettings() {
        return new RenderSettings(color.get(), filled.get(), lineWidth.get());
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        clearSession();
    }

    private void beginSession(ClientLevel level, ResourceLocation dimension) {
        clearSession();
        activeLevel = level;
        activeDimension = dimension;
    }

    private void clearSession() {
        activeLevel = null;
        activeDimension = null;
        moduleTick = 0;
        nextScanTick = 0;
        baselineEstablished = false;
        sessionSeenChunks.clear();
        recentObservations.clear();
    }

    private void observe(long packedPosition) {
        MutableObservation existing = recentObservations.get(packedPosition);
        if (existing != null) {
            existing.lastObservedTick = moduleTick;
            addToSeenFilter(packedPosition);
            return;
        }

        boolean possiblySeen = mightHaveSeen(packedPosition);
        addToSeenFilter(packedPosition);

        Classification classification = baselineEstablished && !possiblySeen
                ? Classification.FIRST_SEEN
                : Classification.BASELINE;
        recentObservations.put(
                packedPosition,
                new MutableObservation(packedPosition, classification, moduleTick)
        );
        enforceMaximumEntries();
    }

    private void pruneExpired() {
        long maximumAgeTicks = lifetime.get() * 20L;
        recentObservations.values().removeIf(
                observation -> moduleTick - observation.firstObservedTick > maximumAgeTicks
        );
    }

    private void enforceMaximumEntries() {
        int maximum = maximumEntries.get();
        Iterator<Long> iterator = recentObservations.keySet().iterator();
        while (recentObservations.size() > maximum && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private boolean mightHaveSeen(long packedPosition) {
        int first = bloomIndex(mix64(packedPosition));
        int second = bloomIndex(mix64(packedPosition ^ 0x9E3779B97F4A7C15L));
        int third = bloomIndex(mix64(packedPosition ^ 0xD1B54A32D192ED03L));
        return sessionSeenChunks.get(first)
                && sessionSeenChunks.get(second)
                && sessionSeenChunks.get(third);
    }

    private void addToSeenFilter(long packedPosition) {
        sessionSeenChunks.set(bloomIndex(mix64(packedPosition)));
        sessionSeenChunks.set(bloomIndex(mix64(packedPosition ^ 0x9E3779B97F4A7C15L)));
        sessionSeenChunks.set(bloomIndex(mix64(packedPosition ^ 0xD1B54A32D192ED03L)));
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

    public enum Classification {
        BASELINE,
        FIRST_SEEN
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
    }

    public record RenderSettings(int argb, boolean filled, double lineWidth) {
    }

    private static final class MutableObservation {
        private final long packedPosition;
        private final Classification classification;
        private final long firstObservedTick;
        private long lastObservedTick;

        private MutableObservation(
                long packedPosition,
                Classification classification,
                long observedTick
        ) {
            this.packedPosition = packedPosition;
            this.classification = classification;
            this.firstObservedTick = observedTick;
            this.lastObservedTick = observedTick;
        }

        private ChunkSnapshot snapshot() {
            int chunkX = ChunkPos.getX(packedPosition);
            int chunkZ = ChunkPos.getZ(packedPosition);
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
}
