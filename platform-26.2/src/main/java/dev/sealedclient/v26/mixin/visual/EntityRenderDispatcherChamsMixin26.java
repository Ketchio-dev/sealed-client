package dev.sealedclient.v26.mixin.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sealedclient.v26.visual.ChamsController26;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.2 render extraction submits immutable states through a node collector.
 * Wrapping that collector covers the base avatar model and every model layer
 * without changing global GPU state.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherChamsMixin26 {
    @Redirect(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/"
                    + "EntityRenderState;"
                    + "Lnet/minecraft/client/renderer/state/level/"
                    + "CameraRenderState;DDD"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/"
                    + "SubmitNodeCollector;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/"
                            + "EntityRenderer;submit("
                            + "Lnet/minecraft/client/renderer/entity/state/"
                            + "EntityRenderState;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/"
                            + "SubmitNodeCollector;"
                            + "Lnet/minecraft/client/renderer/state/level/"
                            + "CameraRenderState;)V"
            ),
            require = 1
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void sealedclient$submitPlayerChams(
            EntityRenderer renderer,
            EntityRenderState state,
            PoseStack poses,
            SubmitNodeCollector collector,
            CameraRenderState camera
    ) {
        renderer.submit(
                state,
                poses,
                ChamsController26.wrapIfActive(state, collector),
                camera
        );
    }
}
