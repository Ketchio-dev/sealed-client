package dev.sealedclient.mixin.camera;

import dev.sealedclient.SealedClient;
import dev.sealedclient.core.Module;
import dev.sealedclient.module.movement.NoRotateModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.HashSet;
import java.util.Set;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerNoRotateMixin {
    @ModifyArgs(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;"
                            + "setValuesFromPositionPacket("
                            + "Lnet/minecraft/world/entity/PositionMoveRotation;"
                            + "Ljava/util/Set;"
                            + "Lnet/minecraft/world/entity/Entity;Z)Z"
            )
    )
    private void sealedclient$preserveLocalRotation(Args args) {
        if (!sealedclient$isNoRotateEnabled()) {
            return;
        }

        PositionMoveRotation correction = args.get(0);
        Set<Relative> relatives = args.get(1);
        Entity player = args.get(2);

        Set<Relative> positionRelatives = new HashSet<>(relatives);
        positionRelatives.remove(Relative.Y_ROT);
        positionRelatives.remove(Relative.X_ROT);

        args.set(0, new PositionMoveRotation(
                correction.position(),
                correction.deltaMovement(),
                player.getYRot(),
                player.getXRot()
        ));
        args.set(1, positionRelatives);
    }

    @Redirect(
            method = "handleRotatePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/"
                            + "ClientboundPlayerRotationPacket;yRot()F"
            )
    )
    private float sealedclient$preserveLocalYaw(ClientboundPlayerRotationPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        return sealedclient$isNoRotateEnabled() && minecraft.player != null
                ? minecraft.player.getYRot()
                : packet.yRot();
    }

    @Redirect(
            method = "handleRotatePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/"
                            + "ClientboundPlayerRotationPacket;xRot()F"
            )
    )
    private float sealedclient$preserveLocalPitch(ClientboundPlayerRotationPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        return sealedclient$isNoRotateEnabled() && minecraft.player != null
                ? minecraft.player.getXRot()
                : packet.xRot();
    }

    private static boolean sealedclient$isNoRotateEnabled() {
        return SealedClient.isInitialized()
                && SealedClient.runtime()
                .modules()
                .find(NoRotateModule.ID)
                .map(Module::isEnabled)
                .orElse(false);
    }
}
