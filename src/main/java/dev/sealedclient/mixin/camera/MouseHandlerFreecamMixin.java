package dev.sealedclient.mixin.camera;

import dev.sealedclient.module.visual.FreecamModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sends mouse look to the detached Freecam entity instead of rotating the real
 * player. The original call is preserved whenever Freecam is not active.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerFreecamMixin {
    @Redirect(
            method = "turnPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"
            )
    )
    private void sealedclient$redirectFreecamTurn(
            LocalPlayer player,
            double horizontal,
            double vertical
    ) {
        if (!FreecamModule.redirectMouseTurn(player, horizontal, vertical)) {
            player.turn(horizontal, vertical);
        }
    }
}
