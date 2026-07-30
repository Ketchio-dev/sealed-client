package dev.sealedclient.v26.mixin.visual;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.class)
public interface RenderTypeInvoker26 {
    @Invoker("create")
    static RenderType sealedclient$create(
            String name,
            RenderSetup setup
    ) {
        throw new AssertionError("mixin not applied");
    }
}
