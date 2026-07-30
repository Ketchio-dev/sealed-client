package dev.sealedclient.v26.mixin.visual;

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
    RenderPipeline sealedclient$getPipeline();

    @Accessor("textures")
    @SuppressWarnings("rawtypes")
    Map sealedclient$getTextures();

    @Accessor("useLightmap")
    boolean sealedclient$getUseLightmap();

    @Accessor("useOverlay")
    boolean sealedclient$getUseOverlay();

    @Accessor("layeringTransform")
    LayeringTransform sealedclient$getLayeringTransform();

    @Accessor("outputTarget")
    OutputTarget sealedclient$getOutputTarget();

    @Accessor("textureTransform")
    TextureTransform sealedclient$getTextureTransform();

    @Accessor("outlineProperty")
    RenderSetup.OutlineProperty sealedclient$getOutlineProperty();

    @Accessor("affectsCrumbling")
    boolean sealedclient$getAffectsCrumbling();

    @Accessor("sortOnUpload")
    boolean sealedclient$getSortOnUpload();
}
