package dev.sealedclient.v26.mixin.hud;

import dev.sealedclient.v26.hud.HudMetricsBridge26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts only the authoritative local protected-from-death entity event after
 * vanilla has transferred packet handling to the client thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTotemMixin26 {
    @Inject(
            method = "handleEntityEvent(Lnet/minecraft/network/protocol/game/"
                    + "ClientboundEntityEventPacket;)V",
            at = @At("TAIL")
    )
    private void sealedclient$observeLocalTotemPop(
            ClientboundEntityEventPacket packet,
            CallbackInfo callback
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        Entity entity = packet.getEntity(player.level());
        if (entity == null) {
            return;
        }
        ClientPacketListener listener =
                (ClientPacketListener) (Object) this;
        HudMetricsBridge26.bind(listener.getConnection(), player);
        HudMetricsBridge26.observeEntityEvent(
                listener.getConnection(),
                entity,
                packet.getEventId()
        );
    }
}
