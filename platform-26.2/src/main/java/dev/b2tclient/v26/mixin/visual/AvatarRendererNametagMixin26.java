package dev.b2tclient.v26.mixin.visual;

import dev.b2tclient.v26.visual.VisualOverlayService26;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the vanilla player name from overlapping the enhanced nametag.
 *
 * <p>The explicit descriptor targets only the Avatar overload. Other living
 * entities and custom entity labels retain vanilla behaviour. The service
 * fails open when it cannot prove that its own nametag is eligible.</p>
 */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererNametagMixin26 {
    @Inject(
            method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void b2t$hideReplacedPlayerNametag(
            Avatar avatar,
            double distanceSquared,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (avatar instanceof AbstractClientPlayer player
                && VisualOverlayService26.suppressVanillaNametag(
                        player,
                        distanceSquared
                )) {
            callback.setReturnValue(false);
        }
    }
}
