package dev.sealedclient.v26.mixin.utility;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Typed access to Minecraft's vanilla right-click cooldown counter.
 *
 * <p>26.2 is unobfuscated and names the field {@code rightClickDelay}. Using a
 * Mixin accessor keeps the access compile-time checked and removes the need for
 * runtime reflection, which the security boundary confines to the optional
 * Baritone adapter.</p>
 */
@Mixin(Minecraft.class)
public interface MinecraftRightClickDelayAccessor26 {
    @Accessor("rightClickDelay")
    int sealed$rightClickDelay();

    @Accessor("rightClickDelay")
    void sealed$setRightClickDelay(int delayTicks);
}
