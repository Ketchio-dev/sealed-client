package dev.b2tclient.v26.mixin.movement;

import dev.b2tclient.v26.movement.MovementInputAutomation26;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces only the item-use multiplier inside 26.2's private input modifier.
 *
 * <p>The base 0.98 input scale, crouch scale, item-use state, sprint rules,
 * and packet flow remain vanilla.</p>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerNoSlowMixin26 {
    @Shadow
    private float itemUseSpeedMultiplier() {
        throw new AssertionError("mixin shadow");
    }

    @Redirect(
            method = "modifyInput(Lnet/minecraft/world/phys/Vec2;)"
                    + "Lnet/minecraft/world/phys/Vec2;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;"
                            + "itemUseSpeedMultiplier()F"
            )
    )
    private float b2tclient$preserveItemUseInput(LocalPlayer player) {
        if (MovementInputAutomation26.shouldBypassItemSlowdown(player)) {
            return 1.0F;
        }
        return itemUseSpeedMultiplier();
    }
}
