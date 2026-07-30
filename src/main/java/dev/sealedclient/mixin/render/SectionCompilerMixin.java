package dev.sealedclient.mixin.render;

import dev.sealedclient.module.visual.XRayModule;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"
            )
    )
    private boolean sealed$openOcclusionGraph(BlockState state) {
        return XRayModule.active() == null && state.isSolidRender();
    }

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;hasBlockEntity()Z"
            )
    )
    private boolean sealed$filterBlockEntities(BlockState state) {
        XRayModule xray = XRayModule.active();
        return state.hasBlockEntity() && (xray == null || xray.isTarget(state));
    }
}
