package dev.sealedclient.mixin.render;

import dev.sealedclient.module.visual.XRayModule;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
abstract class BlockFaceMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void sealed$exposeTargetFaces(
            BlockState state,
            BlockState adjacentState,
            Direction side,
            CallbackInfoReturnable<Boolean> callback
    ) {
        XRayModule xray = XRayModule.active();
        if (xray != null && xray.isTarget(state)) {
            callback.setReturnValue(true);
        }
    }
}
