package dev.b2tclient.v26.mixin.visual;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.b2tclient.v26.visual.XRayController26;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * O(1), allocation-free XRay decisions for asynchronous section compilation.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerXRayMixin26 {
    @Unique
    private static final ThreadLocal<XRayController26.RenderDirective>
            B2TCLIENT_XRAY_DIRECTIVE = new ThreadLocal<>();

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/"
                            + "BlockState;isSolidRender()Z"
            )
    )
    private boolean b2tclient$openXRayOcclusionGraph(BlockState state) {
        return XRayController26.shouldMarkOpaque(state);
    }

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/"
                            + "BlockState;hasBlockEntity()Z"
            )
    )
    private boolean b2tclient$filterXRayBlockEntities(BlockState state) {
        return XRayController26.shouldRenderBlockEntity(state);
    }

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/"
                            + "FluidState;isEmpty()Z"
            )
    )
    private boolean b2tclient$filterXRayFluids(FluidState state) {
        return XRayController26.isFluidEffectivelyEmpty(state);
    }

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/"
                            + "BlockState;getRenderShape()"
                            + "Lnet/minecraft/world/level/block/RenderShape;"
            )
    )
    private RenderShape b2tclient$filterXRayBlockModels(
            BlockState state
    ) {
        return XRayController26.filterRenderShape(
                state,
                state.getRenderShape()
        );
    }

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/"
                            + "ModelBlockRenderer;forceOpaque("
                            + "ZLnet/minecraft/world/level/block/state/"
                            + "BlockState;)Z"
            )
    )
    private boolean b2tclient$keepXRayTransparencyLayer(
            boolean cutoutLeaves,
            BlockState state
    ) {
        if (XRayController26.directive(state).mode()
                == XRayController26.RenderMode.TRANSLUCENT) {
            return false;
        }
        return ModelBlockRenderer.forceOpaque(cutoutLeaves, state);
    }

    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/"
                            + "ModelBlockRenderer;tesselateBlock("
                            + "Lnet/minecraft/client/renderer/block/"
                            + "BlockQuadOutput;FFF"
                            + "Lnet/minecraft/client/renderer/block/"
                            + "BlockAndTintGetter;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/"
                            + "BlockState;"
                            + "Lnet/minecraft/client/renderer/block/dispatch/"
                            + "BlockStateModel;J)V"
            )
    )
    private void b2tclient$tesselateXRayBlock(
            ModelBlockRenderer renderer,
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockStateModel model,
            long seed,
            Operation<Void> original
    ) {
        XRayController26.RenderDirective directive =
                XRayController26.directive(state);
        if (directive.mode() == XRayController26.RenderMode.HIDDEN) {
            return;
        }
        if (directive.mode() != XRayController26.RenderMode.TRANSLUCENT) {
            original.call(
                    renderer, output, x, y, z, level, position, state, model, seed
            );
            return;
        }

        BlockQuadOutput tinted = (
                quadX,
                quadY,
                quadZ,
                quad,
                instance
        ) -> b2tclient$putTranslucentQuad(
                output,
                directive,
                quadX,
                quadY,
                quadZ,
                quad,
                instance
        );
        XRayController26.RenderDirective previous =
                B2TCLIENT_XRAY_DIRECTIVE.get();
        B2TCLIENT_XRAY_DIRECTIVE.set(directive);
        try {
            original.call(
                    renderer, tinted, x, y, z, level, position, state, model, seed
            );
        } finally {
            if (previous == null) {
                B2TCLIENT_XRAY_DIRECTIVE.remove();
            } else {
                B2TCLIENT_XRAY_DIRECTIVE.set(previous);
            }
        }
    }

    @Redirect(
            method = "lambda$compile$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/"
                            + "geometry/BakedQuad$MaterialInfo;layer()"
                            + "Lnet/minecraft/client/renderer/chunk/"
                            + "ChunkSectionLayer;"
            ),
            require = 1
    )
    private ChunkSectionLayer b2tclient$useXRayTranslucentLayer(
            BakedQuad.MaterialInfo material
    ) {
        XRayController26.RenderDirective directive =
                B2TCLIENT_XRAY_DIRECTIVE.get();
        if (directive != null
                && directive.mode()
                == XRayController26.RenderMode.TRANSLUCENT) {
            return ChunkSectionLayer.TRANSLUCENT;
        }
        return material.layer();
    }

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/"
                            + "FluidRenderer;tesselate("
                            + "Lnet/minecraft/client/renderer/block/"
                            + "BlockAndTintGetter;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/client/renderer/block/"
                            + "FluidRenderer$Output;"
                            + "Lnet/minecraft/world/level/block/state/"
                            + "BlockState;"
                            + "Lnet/minecraft/world/level/material/"
                            + "FluidState;)V"
            )
    )
    private void b2tclient$tesselateXRayFluid(
            FluidRenderer renderer,
            BlockAndTintGetter level,
            BlockPos position,
            FluidRenderer.Output output,
            BlockState state,
            FluidState fluid
    ) {
        XRayController26.RenderDirective directive =
                XRayController26.directive(state);
        if (directive.mode() == XRayController26.RenderMode.HIDDEN) {
            return;
        }
        if (directive.mode() != XRayController26.RenderMode.TRANSLUCENT) {
            renderer.tesselate(level, position, output, state, fluid);
            return;
        }
        float opacity = directive.opacityPercent() / 100.0F;
        VertexConsumer translucent = new OpacityVertexConsumer26(
                output.getBuilder(ChunkSectionLayer.TRANSLUCENT),
                opacity
        );
        renderer.tesselate(
                level,
                position,
                ignoredLayer -> translucent,
                state,
                fluid
        );
    }

    @Unique
    private static void b2tclient$putTranslucentQuad(
            BlockQuadOutput output,
            XRayController26.RenderDirective directive,
            float x,
            float y,
            float z,
            BakedQuad quad,
            QuadInstance instance
    ) {
        int color0 = instance.getColor(0);
        int color1 = instance.getColor(1);
        int color2 = instance.getColor(2);
        int color3 = instance.getColor(3);
        int alpha = Math.round(
                directive.opacityPercent() * 255.0F / 100.0F
        );
        instance.multiplyColor((alpha << 24) | 0x00FFFFFF);
        try {
            output.put(x, y, z, quad, instance);
        } finally {
            instance.setColor(0, color0);
            instance.setColor(1, color1);
            instance.setColor(2, color2);
            instance.setColor(3, color3);
        }
    }

    @Unique
    private static final class OpacityVertexConsumer26
            implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float opacity;

        private OpacityVertexConsumer26(
                VertexConsumer delegate,
                float opacity
        ) {
            this.delegate = delegate;
            this.opacity = opacity;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(
                int red,
                int green,
                int blue,
                int alpha
        ) {
            delegate.setColor(
                    red,
                    green,
                    blue,
                    Math.round(alpha * opacity)
            );
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            int alpha = Math.round((color >>> 24 & 0xFF) * opacity);
            delegate.setColor((alpha << 24) | (color & 0x00FFFFFF));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }
}
