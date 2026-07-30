package dev.sealedclient.mixin;

import dev.sealedclient.SealedClient;
import dev.sealedclient.event.PacketEvent;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;"
                    + "Lnet/minecraft/network/PacketSendListener;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sealedclient$onSend(
            Packet<?> packet,
            PacketSendListener listener,
            boolean flush,
            CallbackInfo callback
    ) {
        if (!SealedClient.isInitialized()) {
            return;
        }
        PacketEvent event = SealedClient.runtime().events().post(
                new PacketEvent(packet, PacketEvent.Direction.OUTBOUND)
        );
        if (event.isCancelled()) {
            callback.cancel();
        }
    }

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;"
                    + "Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sealedclient$onReceive(
            ChannelHandlerContext context,
            Packet<?> packet,
            CallbackInfo callback
    ) {
        if (!SealedClient.isInitialized()) {
            return;
        }
        PacketEvent event = SealedClient.runtime().events().post(
                new PacketEvent(packet, PacketEvent.Direction.INBOUND)
        );
        if (event.isCancelled()) {
            callback.cancel();
        }
    }
}
