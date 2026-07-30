package dev.b2tclient.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.b2tclient.module.visual.ChamsModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Redirect(
            method = "render(Lnet/minecraft/world/entity/Entity;DDDF"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I"
                    + "Lnet/minecraft/client/renderer/entity/EntityRenderer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render"
                            + "(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
            )
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void b2t$renderPlayerChams(
            EntityRenderer renderer,
            EntityRenderState state,
            PoseStack poses,
            MultiBufferSource consumers,
            int packedLight
    ) {
        ChamsModule chams = state instanceof PlayerRenderState playerState
                ? ChamsModule.activeFor(playerState)
                : null;
        renderer.render(
                state,
                poses,
                chams == null ? consumers : chams.wrap(consumers),
                packedLight
        );
    }
}
