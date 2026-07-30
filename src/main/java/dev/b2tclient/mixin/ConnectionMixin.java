package dev.b2tclient.mixin;

import dev.b2tclient.B2TClient;
import dev.b2tclient.event.PacketEvent;
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
    private void b2tclient$onSend(
            Packet<?> packet,
            PacketSendListener listener,
            boolean flush,
            CallbackInfo callback
    ) {
        if (!B2TClient.isInitialized()) {
            return;
        }
        PacketEvent event = B2TClient.runtime().events().post(
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
    private void b2tclient$onReceive(
            ChannelHandlerContext context,
            Packet<?> packet,
            CallbackInfo callback
    ) {
        if (!B2TClient.isInitialized()) {
            return;
        }
        PacketEvent event = B2TClient.runtime().events().post(
                new PacketEvent(packet, PacketEvent.Direction.INBOUND)
        );
        if (event.isCancelled()) {
            callback.cancel();
        }
    }
}
