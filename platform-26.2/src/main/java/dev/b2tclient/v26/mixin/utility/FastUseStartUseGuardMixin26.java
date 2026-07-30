package dev.b2tclient.v26.mixin.utility;

import dev.b2tclient.v26.utility.FastUseAutomation26;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Revalidates a Fast Use cooldown lease at the actual vanilla use boundary.
 */
@Mixin(Minecraft.class)
public abstract class FastUseStartUseGuardMixin26 {
    @Shadow
    private void startUseItem() {
        throw new AssertionError("Mixin shadow was not transformed");
    }

    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;"
                            + "startUseItem()V",
                    ordinal = 1
            ),
            require = 1
    )
    private void b2t$guardFastUseHeldRepeat(Minecraft client) {
        if (!FastUseAutomation26.blockUnsafeHeldRepeat(client)) {
            startUseItem();
        }
    }
}
