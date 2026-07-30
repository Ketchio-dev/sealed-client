package dev.sealedclient.v26.mixin.hud;

import dev.sealedclient.v26.hud.HudMetricsBridge26;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes, but never consumes or rewrites, inbound time packets and clears
 * local metrics when the exact bound channel closes.
 */
@Mixin(Connection.class)
public abstract class ConnectionHudMetricsMixin26 {
    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;"
                    + "Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD")
    )
    private void sealedclient$observeHudMetrics(
            ChannelHandlerContext context,
            Packet<?> packet,
            CallbackInfo callback
    ) {
        HudMetricsBridge26.observeInbound(
                (Connection) (Object) this,
                packet
        );
    }

    @Inject(
            method = "channelInactive(Lio/netty/channel/"
                    + "ChannelHandlerContext;)V",
            at = @At("HEAD")
    )
    private void sealedclient$disconnectHudMetrics(
            ChannelHandlerContext context,
            CallbackInfo callback
    ) {
        HudMetricsBridge26.disconnect((Connection) (Object) this);
    }
}
