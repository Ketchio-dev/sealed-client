package dev.b2tclient.v26.mixin;

import dev.b2tclient.v26.B2TClient26;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes inbound timing to the movement safety policy without cancelling,
 * rewriting, retaining, or otherwise consuming the packet.
 */
@Mixin(Connection.class)
public abstract class ConnectionMovementMixin26 {
    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;"
                    + "Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD")
    )
    private void b2tclient$observeMovementNetwork(
            ChannelHandlerContext context,
            Packet<?> packet,
            CallbackInfo callback
    ) {
        B2TClient26.runtime().observeInbound(
                (Connection) (Object) this,
                packet
        );
    }
}
