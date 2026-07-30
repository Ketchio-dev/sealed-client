package dev.b2tclient.v26.mixin.visual;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.class)
public interface RenderTypeAccessor26 {
    @Accessor("state")
    RenderSetup b2tclient$getState();
}
