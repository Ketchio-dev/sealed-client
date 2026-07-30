package dev.b2tclient.mixin.render;

import dev.b2tclient.module.visual.XRayModule;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemBlockRenderTypes.class)
abstract class ItemBlockRenderTypesMixin {
    @Inject(method = "getChunkRenderType", at = @At("HEAD"), cancellable = true)
    private static void b2t$useTranslucentLayer(
            BlockState state,
            CallbackInfoReturnable<RenderType> callback
    ) {
        XRayModule xray = XRayModule.active();
        if (xray != null && xray.makesTransparent(state)) {
            callback.setReturnValue(RenderType.translucent());
        }
    }

    @Inject(
            method = "getRenderLayer(Lnet/minecraft/world/level/material/FluidState;)"
                    + "Lnet/minecraft/client/renderer/RenderType;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void b2t$useTranslucentFluidLayer(
            FluidState state,
            CallbackInfoReturnable<RenderType> callback
    ) {
        XRayModule xray = XRayModule.active();
        if (xray != null && xray.makesTransparent(state.createLegacyBlock())) {
            callback.setReturnValue(RenderType.translucent());
        }
    }
}
