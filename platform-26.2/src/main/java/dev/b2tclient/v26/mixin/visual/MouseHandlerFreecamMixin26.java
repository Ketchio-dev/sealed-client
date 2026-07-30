package dev.b2tclient.v26.mixin.visual;

import dev.b2tclient.v26.visual.FreecamController26;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes mouse look to the detached camera without rotating the networked
 * player. Vanilla is retained whenever the controller is not fully attached.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerFreecamMixin26 {
    @Redirect(
            method = "turnPlayer(D)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;"
                            + "turn(DD)V"
            )
    )
    private void b2tclient$redirectFreecamTurn(
            LocalPlayer player,
            double horizontal,
            double vertical
    ) {
        if (!FreecamController26.redirectMouseTurn(
                player,
                horizontal,
                vertical
        )) {
            player.turn(horizontal, vertical);
        }
    }
}
