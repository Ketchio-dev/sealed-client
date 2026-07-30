package dev.b2tclient.v26.visual;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.b2tclient.v26.mixin.visual.RenderSetupAccessor26;
import dev.b2tclient.v26.mixin.visual.RenderSetupInvoker26;
import dev.b2tclient.v26.mixin.visual.RenderTypeAccessor26;
import dev.b2tclient.v26.mixin.visual.RenderTypeInvoker26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Player-only Chams hook for the 26.2 submit-node renderer.
 *
 * <p>Every transformed {@link RenderType} uses an immutable pipeline with
 * {@link CompareOp#ALWAYS_PASS} and depth writes disabled. No global render
 * state is touched, so later nodes automatically use their original depth and
 * blend state.</p>
 */
public final class ChamsController26 {
    public static final int MAX_CACHED_RENDER_TYPES = 256;
    private static final AtomicInteger PIPELINE_SEQUENCE = new AtomicInteger();
    private static final Map<RenderType, RenderType> THROUGH_WALL_CACHE =
            new IdentityHashMap<>();
    private static volatile HookSnapshot hooks = HookSnapshot.disabled();

    private Configuration configuration = Configuration.DEFAULT;

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
     * Publishes player identity and settings without reading Minecraft state
     * from the render submission hook.
     */
    public void tick(Minecraft client, boolean enabled) {
        int localPlayerId = client != null && client.player != null
                ? client.player.getId()
                : Integer.MIN_VALUE;
        boolean ready = enabled
                && client != null
                && client.level != null
                && client.player != null
                && client.player.isAlive()
                && !client.player.isDeadOrDying();
        hooks = ready
                ? new HookSnapshot(
                true,
                configuration.color(),
                configuration.showSelf(),
                localPlayerId
        )
                : HookSnapshot.disabled();
    }

    public void release() {
        hooks = HookSnapshot.disabled();
    }

    /**
     * Intended for final client shutdown or resource reload only.
     */
    public void clearRenderTypeCache() {
        synchronized (THROUGH_WALL_CACHE) {
            THROUGH_WALL_CACHE.clear();
        }
    }

    public static boolean active() {
        return hooks.enabled();
    }

    public static HookSnapshot snapshot() {
        return hooks;
    }

    /**
     * Wraps only player/avatar submission and returns the original collector
     * for all other entity states.
     */
    public static SubmitNodeCollector wrapIfActive(
            EntityRenderState state,
            SubmitNodeCollector delegate
    ) {
        if (delegate == null) {
            return null;
        }
        HookSnapshot snapshot = hooks;
        if (!snapshot.enabled()
                || !(state instanceof AvatarRenderState avatar)
                || (!snapshot.showSelf()
                && avatar.id == snapshot.localPlayerId())) {
            return delegate;
        }
        return new ChamsSubmitNodeCollector26(delegate, snapshot.color());
    }

    static RenderTransform transform(
            RenderType original,
            int originalColor,
            int tint
    ) {
        if (original == null) {
            return null;
        }
        RenderType throughWalls = throughWalls(original);
        if (throughWalls == null || throughWalls == original) {
            return null;
        }
        return new RenderTransform(
                throughWalls,
                multiplyArgb(originalColor, tint)
        );
    }

    static int multiplyArgb(int first, int second) {
        int alpha = (first >>> 24 & 0xFF) * (second >>> 24 & 0xFF) / 255;
        int red = (first >>> 16 & 0xFF) * (second >>> 16 & 0xFF) / 255;
        int green = (first >>> 8 & 0xFF) * (second >>> 8 & 0xFF) / 255;
        int blue = (first & 0xFF) * (second & 0xFF) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static RenderType throughWalls(RenderType original) {
        synchronized (THROUGH_WALL_CACHE) {
            RenderType cached = THROUGH_WALL_CACHE.get(original);
            if (cached != null) {
                return cached;
            }
            if (THROUGH_WALL_CACHE.size() >= MAX_CACHED_RENDER_TYPES) {
                return null;
            }
            try {
                RenderSetup originalSetup =
                        ((RenderTypeAccessor26) (Object) original)
                                .b2tclient$getState();
                RenderSetupAccessor26 setup =
                        (RenderSetupAccessor26) (Object) originalSetup;
                RenderPipeline pipeline = createThroughWallPipeline(
                        setup.b2tclient$getPipeline()
                );
                @SuppressWarnings("rawtypes")
                Map textures = setup.b2tclient$getTextures();
                RenderSetup transformedSetup =
                        RenderSetupInvoker26.b2tclient$create(
                                pipeline,
                                textures,
                                setup.b2tclient$getUseLightmap(),
                                setup.b2tclient$getUseOverlay(),
                                setup.b2tclient$getLayeringTransform(),
                                setup.b2tclient$getOutputTarget(),
                                setup.b2tclient$getTextureTransform(),
                                setup.b2tclient$getOutlineProperty(),
                                setup.b2tclient$getAffectsCrumbling(),
                                setup.b2tclient$getSortOnUpload()
                        );
                RenderType transformed =
                        RenderTypeInvoker26.b2tclient$create(
                                "b2t_chams_"
                                        + PIPELINE_SEQUENCE.get(),
                                transformedSetup
                        );
                THROUGH_WALL_CACHE.put(original, transformed);
                return transformed;
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                return null;
            }
        }
    }

    private static RenderPipeline createThroughWallPipeline(
            RenderPipeline original
    ) {
        ColorTargetState[] colorTargets =
                original.getColorTargetStates().clone();
        int activeColorTargets = 0;
        for (int index = 0; index < colorTargets.length; index++) {
            ColorTargetState state = colorTargets[index];
            if (state == null) {
                continue;
            }
            colorTargets[index] = new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT),
                    state.format(),
                    state.writeMask()
            );
            activeColorTargets = index + 1;
        }
        VertexFormat[] vertexFormats =
                original.getVertexFormatBindings().clone();
        RenderPipeline.Snippet copied = new RenderPipeline.Snippet(
                Optional.of(original.getVertexShader()),
                Optional.of(original.getFragmentShader()),
                Optional.of(original.getShaderDefines()),
                Optional.of(List.copyOf(original.getBindGroupLayouts())),
                colorTargets,
                activeColorTargets,
                Optional.of(throughWallDepthState()),
                Optional.of(original.getPolygonMode()),
                Optional.of(original.isCull()),
                vertexFormats,
                Optional.of(original.getPrimitiveTopology())
        );
        int sequence = PIPELINE_SEQUENCE.incrementAndGet();
        return RenderPipeline.builder(copied)
                .withLocation(Identifier.fromNamespaceAndPath(
                        "b2tclient",
                        "chams/through_walls_" + sequence
                ))
                .build();
    }

    static DepthStencilState throughWallDepthState() {
        return new DepthStencilState(CompareOp.ALWAYS_PASS, false);
    }

    public record Configuration(int color, boolean showSelf) {
        public static final Configuration DEFAULT =
                new Configuration(0xA0FF5555, false);

        public Configuration {
            if ((color >>> 24 & 0xFF) == 0) {
                throw new IllegalArgumentException(
                        "chams color must have non-zero alpha"
                );
            }
        }
    }

    public record HookSnapshot(
            boolean enabled,
            int color,
            boolean showSelf,
            int localPlayerId
    ) {
        public static HookSnapshot disabled() {
            return new HookSnapshot(
                    false,
                    Configuration.DEFAULT.color(),
                    false,
                    Integer.MIN_VALUE
            );
        }
    }

    record RenderTransform(RenderType renderType, int color) {
    }
}
