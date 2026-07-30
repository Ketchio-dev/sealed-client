package dev.sealedclient.mixin.camera;

import dev.sealedclient.module.movement.NoSlowModule;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips only LocalPlayer's vanilla use-item input reduction. The player's
 * real item-use state remains unchanged everywhere else.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerNoSlowMixin {
    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z",
                    ordinal = 0
            )
    )
    private boolean sealedclient$skipUseItemMovementSlowdown(LocalPlayer player) {
        return !NoSlowModule.shouldBypassUseItemSlowdown() && player.isUsingItem();
    }
}
