package dev.b2tclient.v26.mixin.visual;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor26 {
    @Accessor("pipeline")
    RenderPipeline b2tclient$getPipeline();

    @Accessor("textures")
    @SuppressWarnings("rawtypes")
    Map b2tclient$getTextures();

    @Accessor("useLightmap")
    boolean b2tclient$getUseLightmap();

    @Accessor("useOverlay")
    boolean b2tclient$getUseOverlay();

    @Accessor("layeringTransform")
    LayeringTransform b2tclient$getLayeringTransform();

    @Accessor("outputTarget")
    OutputTarget b2tclient$getOutputTarget();

    @Accessor("textureTransform")
    TextureTransform b2tclient$getTextureTransform();

    @Accessor("outlineProperty")
    RenderSetup.OutlineProperty b2tclient$getOutlineProperty();

    @Accessor("affectsCrumbling")
    boolean b2tclient$getAffectsCrumbling();

    @Accessor("sortOnUpload")
    boolean b2tclient$getSortOnUpload();
}
