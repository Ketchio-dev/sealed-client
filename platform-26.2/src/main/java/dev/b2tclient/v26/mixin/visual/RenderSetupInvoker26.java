package dev.b2tclient.v26.mixin.visual;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(RenderSetup.class)
public interface RenderSetupInvoker26 {
    @Invoker("<init>")
    static RenderSetup b2tclient$create(
            RenderPipeline pipeline,
            @SuppressWarnings("rawtypes") Map textures,
            boolean useLightmap,
            boolean useOverlay,
            LayeringTransform layeringTransform,
            OutputTarget outputTarget,
            TextureTransform textureTransform,
            RenderSetup.OutlineProperty outlineProperty,
            boolean affectsCrumbling,
            boolean sortOnUpload
    ) {
        throw new AssertionError("mixin not applied");
    }
}
