package dev.sealedclient.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sealedclient.module.visual.XRayModule;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockRenderDispatcher.class)
abstract class BlockRenderDispatcherMixin {
    @Redirect(
            method = "renderBatched",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock"
                            + "(Lnet/minecraft/world/level/BlockAndTintGetter;"
                            + "Lnet/minecraft/client/resources/model/BakedModel;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "ZLnet/minecraft/util/RandomSource;JI)V"
            )
    )
    private void sealed$filterBlock(
            ModelBlockRenderer renderer,
            BlockAndTintGetter level,
            BakedModel model,
            BlockState state,
            BlockPos position,
            PoseStack poses,
            VertexConsumer consumer,
            boolean checkSides,
            RandomSource random,
            long seed,
            int overlay
    ) {
        XRayModule xray = XRayModule.active();
        if (xray != null && xray.hides(state)) {
            return;
        }
        VertexConsumer output = xray != null && xray.makesTransparent(state)
                ? xray.opacityConsumer(consumer)
                : consumer;
        renderer.tesselateBlock(
                level,
                model,
                state,
                position,
                poses,
                output,
                checkSides,
                random,
                seed,
                overlay
        );
    }

    @Redirect(
            method = "renderLiquid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/LiquidBlockRenderer;tesselate"
                            + "(Lnet/minecraft/world/level/BlockAndTintGetter;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/world/level/material/FluidState;)V"
            )
    )
    private void sealed$filterLiquid(
            LiquidBlockRenderer renderer,
            BlockAndTintGetter level,
            BlockPos position,
            VertexConsumer consumer,
            BlockState state,
            FluidState fluid
    ) {
        XRayModule xray = XRayModule.active();
        if (xray != null && xray.hides(state)) {
            return;
        }
        VertexConsumer output = xray != null && xray.makesTransparent(state)
                ? xray.opacityConsumer(consumer)
                : consumer;
        renderer.tesselate(level, position, output, state, fluid);
    }
}
