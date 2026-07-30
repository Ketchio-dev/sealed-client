package dev.sealedclient.v26.mixin.movement;

import dev.sealedclient.v26.movement.MovementInputAutomation26;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import dev.sealedclient.v26.movement.NoRotatePolicy26;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Preserves only local camera rotation. Position, velocity, relative delta
 * rotation, teleport acknowledgement, and vanilla response packets remain
 * intact.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerNoRotateMixin26 {
    @ModifyArgs(
            method = "handleMovePlayer("
                    + "Lnet/minecraft/network/protocol/game/"
                    + "ClientboundPlayerPositionPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/"
                            + "ClientPacketListener;setValuesFromPositionPacket("
                            + "Lnet/minecraft/world/entity/PositionMoveRotation;"
                            + "Ljava/util/Set;"
                            + "Lnet/minecraft/world/entity/Entity;Z)Z"
            )
    )
    private void sealedclient$preserveCameraOnPositionCorrection(Args args) {
        Entity entity = args.get(2);
        if (!(entity instanceof LocalPlayer player)) {
            return;
        }

        net.minecraft.world.entity.PositionMoveRotation correction = args.get(0);
        java.util.Set<?> relatives = args.get(1);
        @SuppressWarnings("unchecked")
        java.util.Set<net.minecraft.world.entity.Relative> typedRelatives =
                (java.util.Set<net.minecraft.world.entity.Relative>) relatives;
        NoRotatePolicy26.PositionDecision decision =
                MovementInputAutomation26.preservePositionCorrection(
                player,
                correction,
                typedRelatives
        );
        if (!NoRotatePolicy26.shouldReplacePositionArguments(decision)) {
            return;
        }
        args.set(0, decision.correction());
        args.set(1, decision.relatives());
    }

    @Redirect(
            method = "handleRotatePlayer("
                    + "Lnet/minecraft/network/protocol/game/"
                    + "ClientboundPlayerRotationPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/"
                            + "Player;setYRot(F)V"
            )
    )
    private void sealedclient$preserveCameraYaw(Player player, float yaw) {
        boolean preserve = player instanceof LocalPlayer local
                && MovementInputAutomation26.shouldPreserveServerYaw(local);
        if (NoRotatePolicy26.shouldApplyServerRotation(preserve)) {
            player.setYRot(yaw);
        }
    }

    @Redirect(
            method = "handleRotatePlayer("
                    + "Lnet/minecraft/network/protocol/game/"
                    + "ClientboundPlayerRotationPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/"
                            + "Player;setXRot(F)V"
            )
    )
    private void sealedclient$preserveCameraPitch(Player player, float pitch) {
        boolean preserve = player instanceof LocalPlayer local
                && MovementInputAutomation26.shouldPreserveServerPitch(local);
        if (NoRotatePolicy26.shouldApplyServerRotation(preserve)) {
            player.setXRot(pitch);
        }
    }
}
