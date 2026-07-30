package dev.b2tclient.v26.mixin;

import dev.b2tclient.v26.movement.SafeWalkGuard26;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reuses Player's vanilla crouch edge-trimming algorithm for Safe Walk.
 *
 * <p>Position, velocity, and packets are not fabricated. The only change is
 * treating an arbitration-authorized local player as staying on the ground
 * surface for one movement tick.</p>
 */
@Mixin(Player.class)
public abstract class SafeWalkPlayerMixin26 {
    @Inject(
            method = "isStayingOnGroundSurface()Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void b2tclient$authorizeSafeWalk(
            CallbackInfoReturnable<Boolean> callback
    ) {
        Player player = (Player) (Object) this;
        if (SafeWalkGuard26.shouldStayOnGround(player)) {
            callback.setReturnValue(true);
        }
    }
}
