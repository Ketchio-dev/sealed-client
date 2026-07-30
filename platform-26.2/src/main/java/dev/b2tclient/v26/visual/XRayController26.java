package dev.b2tclient.v26.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe XRay state and client-thread chunk rebuild lifecycle.
 *
 * <p>Asynchronous section compilation observes only one immutable
 * {@link RenderSnapshot}. It never reads settings, the client level, or mutable
 * registries. Configuration changes are resolved and published on the client
 * thread before a bounded, debounced geometry invalidation.</p>
 */
public final class XRayController26 {
    public static final int MAX_VISIBLE_BLOCKS = 128;
    public static final int MIN_REFRESH_DELAY_TICKS = 1;
    public static final int MAX_REFRESH_DELAY_TICKS = 80;
    private static final int MINIMUM_REBUILD_INTERVAL_TICKS = 10;
    private static volatile RenderSnapshot renderSnapshot =
            RenderSnapshot.disabled();

    private Configuration configuration = Configuration.DEFAULT;
    private Configuration appliedConfiguration;
    private Object observedLevel;
    private boolean appliedEnabled;
    private int refreshCountdown = -1;
    private int ticksSinceRefresh = MINIMUM_REBUILD_INTERVAL_TICKS;
    private long refreshCount;

    public Configuration configuration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    /**
     * Publishes an immutable render snapshot and performs at most one geometry
     * invalidation after the configured debounce.
     */
    public void tick(Minecraft client, boolean enabled) {
        ticksSinceRefresh = Math.min(
                Integer.MAX_VALUE,
                ticksSinceRefresh + 1
        );
        if (!onClientThread(client)) {
            renderSnapshot = RenderSnapshot.disabled();
            appliedEnabled = false;
            refreshCountdown = -1;
            return;
        }

        boolean desiredEnabled = enabled
                && client.level != null
                && client.levelRenderer != null
                && !configuration.visibleBlocks().isEmpty();
        boolean levelChanged = observedLevel != client.level;
        boolean configurationChanged =
                !configuration.equals(appliedConfiguration);
        boolean enabledChanged = desiredEnabled != appliedEnabled;

        if (configurationChanged || enabledChanged || levelChanged) {
            appliedConfiguration = configuration;
            appliedEnabled = desiredEnabled;
            observedLevel = client.level;
            renderSnapshot = desiredEnabled
                    ? resolveSnapshot(configuration)
                    : RenderSnapshot.disabled();
            if (enabledChanged
                    || levelChanged
                    || configuration.autoRefresh()) {
                refreshCountdown = configuration.refreshDelayTicks();
            }
        }

        if (refreshCountdown > 0) {
            refreshCountdown--;
        }
        if (refreshCountdown == 0
                && ticksSinceRefresh >= MINIMUM_REBUILD_INTERVAL_TICKS) {
            refreshCountdown = -1;
            refresh(client);
        }
    }

    /**
     * Immediately disables render filtering and rebuilds geometry when called
     * on the client thread. This is the disconnect/shutdown safety path.
     */
    public void release(Minecraft client) {
        boolean wasEnabled = renderSnapshot.enabled();
        renderSnapshot = RenderSnapshot.disabled();
        appliedEnabled = false;
        appliedConfiguration = null;
        observedLevel = null;
        refreshCountdown = -1;
        if (wasEnabled && onClientThread(client) && client.level != null) {
            refresh(client);
        }
    }

    public long refreshCount() {
        return refreshCount;
    }

    public static RenderSnapshot snapshot() {
        return renderSnapshot;
    }

    public static boolean active() {
        return renderSnapshot.enabled();
    }

    public static boolean isTarget(BlockState state) {
        RenderSnapshot snapshot = renderSnapshot;
        return isTarget(snapshot, state);
    }

    public static boolean shouldRenderBlock(BlockState state) {
        RenderSnapshot snapshot = renderSnapshot;
        return !snapshot.enabled()
                || snapshot.hiddenOpacity() > 0
                || isTarget(snapshot, state);
    }

    public static boolean shouldRenderFluid(FluidState state) {
        RenderSnapshot snapshot = renderSnapshot;
        if (state == null || state.isEmpty()) {
            return false;
        }
        return !snapshot.enabled()
                || snapshot.hiddenOpacity() > 0
                || isTarget(snapshot, state.createLegacyBlock());
    }

    public static boolean isFluidEffectivelyEmpty(FluidState state) {
        if (state == null || state.isEmpty()) {
            return true;
        }
        RenderSnapshot snapshot = renderSnapshot;
        return snapshot.enabled()
                && snapshot.hiddenOpacity() == 0
                && !isTarget(snapshot, state.createLegacyBlock());
    }

    public static boolean shouldMarkOpaque(BlockState state) {
        return !renderSnapshot.enabled()
                && state != null
                && state.isSolidRender();
    }

    public static boolean shouldRenderBlockEntity(BlockState state) {
        RenderSnapshot snapshot = renderSnapshot;
        return state != null
                && state.hasBlockEntity()
                && (!snapshot.enabled() || isTarget(snapshot, state));
    }

    public static RenderShape filterRenderShape(
            BlockState state,
            RenderShape vanillaShape
    ) {
        RenderSnapshot snapshot = renderSnapshot;
        if (!snapshot.enabled()
                || snapshot.hiddenOpacity() > 0
                || isTarget(snapshot, state)) {
            return vanillaShape;
        }
        return RenderShape.INVISIBLE;
    }

    /**
     * XRay targets expose every face so surrounding hidden blocks cannot cull
     * the ore mesh.
     */
    public static boolean shouldExposeFace(BlockState state) {
        RenderSnapshot snapshot = renderSnapshot;
        return snapshot.enabled() && isTarget(snapshot, state);
    }

    public static RenderDirective directive(BlockState state) {
        RenderSnapshot snapshot = renderSnapshot;
        if (!snapshot.enabled() || isTarget(snapshot, state)) {
            return RenderDirective.normal();
        }
        if (snapshot.hiddenOpacity() <= 0) {
            return RenderDirective.hidden();
        }
        if (snapshot.hiddenOpacity() >= 100) {
            return RenderDirective.normal();
        }
        return RenderDirective.translucent(snapshot.hiddenOpacity());
    }

    static Set<String> normalizeWhitelist(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            if (normalized.size() >= MAX_VISIBLE_BLOCKS) {
                break;
            }
            if (raw == null) {
                continue;
            }
            String candidate = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (candidate.isEmpty() || candidate.length() > 128) {
                continue;
            }
            Identifier id = candidate.indexOf(':') >= 0
                    ? Identifier.tryParse(candidate)
                    : Identifier.tryBuild("minecraft", candidate);
            if (id != null) {
                normalized.add(id.toString());
            }
        }
        return Set.copyOf(normalized);
    }

    private static RenderSnapshot resolveSnapshot(
            Configuration configuration
    ) {
        Set<Block> resolved = new LinkedHashSet<>();
        for (String value : configuration.visibleBlocks()) {
            Identifier id = Identifier.tryParse(value);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                resolved.add(BuiltInRegistries.BLOCK.getValue(id));
            }
        }
        if (resolved.isEmpty()) {
            return RenderSnapshot.disabled();
        }
        return new RenderSnapshot(
                true,
                Set.copyOf(resolved),
                configuration.hiddenOpacity()
        );
    }

    private static boolean isTarget(
            RenderSnapshot snapshot,
            BlockState state
    ) {
        return snapshot.enabled()
                && state != null
                && !state.isAir()
                && snapshot.visibleBlocks().contains(state.getBlock());
    }

    private void refresh(Minecraft client) {
        if (!onClientThread(client)
                || client.level == null
                || client.levelRenderer == null
                || client.gameRenderer == null) {
            return;
        }
        client.levelRenderer.invalidateCompiledGeometry(
                client.level,
                client.options,
                client.gameRenderer.mainCamera(),
                client.getBlockColors()
        );
        refreshCount++;
        ticksSinceRefresh = 0;
    }

    private static boolean onClientThread(Minecraft client) {
        return client != null
                && Thread.currentThread() == client.getRunningThread();
    }

    public record Configuration(
            Set<String> visibleBlocks,
            int hiddenOpacity,
            boolean autoRefresh,
            int refreshDelayTicks
    ) {
        public static final Configuration DEFAULT = new Configuration(
                defaultVisibleBlocks(),
                0,
                true,
                4
        );

        public Configuration {
            visibleBlocks = normalizeWhitelist(visibleBlocks);
            if (hiddenOpacity < 0 || hiddenOpacity > 100) {
                throw new IllegalArgumentException(
                        "hiddenOpacity must be in [0, 100]"
                );
            }
            if (refreshDelayTicks < MIN_REFRESH_DELAY_TICKS
                    || refreshDelayTicks > MAX_REFRESH_DELAY_TICKS) {
                throw new IllegalArgumentException(
                        "refreshDelayTicks must be in ["
                                + MIN_REFRESH_DELAY_TICKS
                                + ", "
                                + MAX_REFRESH_DELAY_TICKS
                                + "]"
                );
            }
        }

        public Configuration(
                Set<String> visibleBlocks,
                int refreshDelayTicks
        ) {
            this(visibleBlocks, 0, true, refreshDelayTicks);
        }

        public static Configuration of(
                Collection<String> visibleBlocks,
                int refreshDelayTicks
        ) {
            return new Configuration(
                    normalizeWhitelist(visibleBlocks),
                    0,
                    true,
                    refreshDelayTicks
            );
        }

        public static Configuration of(
                Collection<String> visibleBlocks,
                int hiddenOpacity,
                boolean autoRefresh,
                int refreshDelayTicks
        ) {
            return new Configuration(
                    normalizeWhitelist(visibleBlocks),
                    hiddenOpacity,
                    autoRefresh,
                    refreshDelayTicks
            );
        }

        private static Set<String> defaultVisibleBlocks() {
            List<String> ids = new ArrayList<>(List.of(
                    "minecraft:ancient_debris",
                    "minecraft:coal_ore",
                    "minecraft:copper_ore",
                    "minecraft:deepslate_coal_ore",
                    "minecraft:deepslate_copper_ore",
                    "minecraft:deepslate_diamond_ore",
                    "minecraft:deepslate_emerald_ore",
                    "minecraft:deepslate_gold_ore",
                    "minecraft:deepslate_iron_ore",
                    "minecraft:deepslate_lapis_ore",
                    "minecraft:deepslate_redstone_ore",
                    "minecraft:diamond_ore",
                    "minecraft:emerald_ore",
                    "minecraft:gold_ore",
                    "minecraft:iron_ore",
                    "minecraft:lapis_ore",
                    "minecraft:nether_gold_ore",
                    "minecraft:nether_quartz_ore",
                    "minecraft:redstone_ore"
            ));
            return Set.copyOf(ids);
        }
    }

    public record RenderSnapshot(
            boolean enabled,
            Set<Block> visibleBlocks,
            int hiddenOpacity
    ) {
        public RenderSnapshot {
            visibleBlocks = Set.copyOf(
                    Objects.requireNonNull(visibleBlocks, "visibleBlocks")
            );
            if (hiddenOpacity < 0 || hiddenOpacity > 100) {
                throw new IllegalArgumentException(
                        "hiddenOpacity must be in [0, 100]"
                );
            }
            if (enabled && visibleBlocks.isEmpty()) {
                throw new IllegalArgumentException(
                        "enabled snapshot requires at least one block"
                );
            }
        }

        public RenderSnapshot(boolean enabled, Set<Block> visibleBlocks) {
            this(enabled, visibleBlocks, 0);
        }

        public static RenderSnapshot disabled() {
            return new RenderSnapshot(false, Set.of(), 0);
        }
    }

    public record RenderDirective(RenderMode mode, int opacityPercent) {
        public RenderDirective {
            Objects.requireNonNull(mode, "mode");
            if (opacityPercent < 0 || opacityPercent > 100) {
                throw new IllegalArgumentException(
                        "opacityPercent must be in [0, 100]"
                );
            }
        }

        public static RenderDirective normal() {
            return new RenderDirective(RenderMode.NORMAL, 100);
        }

        public static RenderDirective hidden() {
            return new RenderDirective(RenderMode.HIDDEN, 0);
        }

        public static RenderDirective translucent(int opacityPercent) {
            if (opacityPercent <= 0 || opacityPercent >= 100) {
                throw new IllegalArgumentException(
                        "translucent opacity must be in [1, 99]"
                );
            }
            return new RenderDirective(
                    RenderMode.TRANSLUCENT,
                    opacityPercent
            );
        }
    }

    public enum RenderMode {
        NORMAL,
        HIDDEN,
        TRANSLUCENT
    }
}
