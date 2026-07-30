package dev.sealedclient.v26.mixin.visual;

import dev.sealedclient.v26.visual.XRayController26;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public abstract class BlockFaceXRayMixin26 {
    @Inject(
            method = "shouldRenderFace",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void sealedclient$exposeXRayTargetFaces(
            BlockState state,
            BlockState adjacentState,
            Direction side,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (XRayController26.shouldExposeFace(state)) {
            callback.setReturnValue(true);
        }
    }
}
