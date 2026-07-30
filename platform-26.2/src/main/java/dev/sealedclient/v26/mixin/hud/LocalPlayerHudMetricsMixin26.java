package dev.sealedclient.v26.mixin.hud;

import dev.sealedclient.v26.hud.HudMetricsBridge26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes the real local health/offhand state once per completed client
 * player tick. Merely moving or dropping a totem never increments the count.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerHudMetricsMixin26 {
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void sealedclient$observeLocalHudState(CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = (LocalPlayer) (Object) this;
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null || minecraft.player != player) {
            return;
        }
        HudMetricsBridge26.observeLocalPlayerTick(
                listener.getConnection(),
                player
        );
    }
}
