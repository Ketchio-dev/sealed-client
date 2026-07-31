package dev.sealedclient.module.movement;

import dev.sealedclient.SealedClient;
import dev.sealedclient.platform.MovementInputAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class SealedMovementSupport {
    private static final double BLAST_RESISTANT_THRESHOLD = 600.0;

    private SealedMovementSupport() {
    }

    static boolean canControl(Minecraft minecraft) {
        return minecraft.player != null
                && minecraft.level != null
                && minecraft.screen == null
                && !minecraft.player.isPassenger()
                && !minecraft.player.isSpectator();
    }

    static MovementSafetyController.Observation safetyObservation(Minecraft minecraft) {
        boolean usable = minecraft.player != null
                && minecraft.level != null
                && minecraft.getConnection() != null;
        if (!usable) {
            return new MovementSafetyController.Observation(
                    null,
                    0.0,
                    0.0,
                    0.0,
                    -1,
                    false,
                    0L,
                    -1L
            );
        }

        long context = ((long) System.identityHashCode(minecraft.level) << 32)
                ^ Integer.toUnsignedLong(System.identityHashCode(minecraft.player));
        var playerInfo = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        int latency = playerInfo == null ? -1 : playerInfo.getLatency();
        MovementNetworkTracker.Snapshot network = SealedClient.isInitialized()
                ? SealedClient.runtime().movementNetwork().snapshot()
                : new MovementNetworkTracker.Snapshot(0L, -1L);
        return new MovementSafetyController.Observation(
                context,
                minecraft.player.getX(),
                minecraft.player.getY(),
                minecraft.player.getZ(),
                latency,
                true,
                network.correctionSequence(),
                network.inboundSilenceMillis()
        );
    }

    static Vec3 inputDirection(LocalPlayer player) {
        double side = MovementInputAccess.left(player);
        double forward = MovementInputAccess.forward(player);
        double length = Math.hypot(side, forward);
        if (length < 1.0E-4) {
            return Vec3.ZERO;
        }

        side /= Math.max(1.0, length);
        forward /= Math.max(1.0, length);
        double radians = Math.toRadians(player.getYRot());
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new Vec3(side * cos - forward * sin, 0.0, forward * cos + side * sin);
    }

    static Vec3 horizontalAtMost(Vec3 movement, double maximum) {
        double horizontal = movement.horizontalDistance();
        if (horizontal <= maximum || horizontal < 1.0E-6) {
            return movement;
        }
        double scale = maximum / horizontal;
        return new Vec3(movement.x * scale, movement.y, movement.z * scale);
    }

    static BlockPos findNearestSafeHole(Level level, LocalPlayer player, int radius) {
        BlockPos origin = BlockPos.containing(player.getX(), player.getY() + 0.05, player.getZ());
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = origin.offset(x, yOffset, z);
                    if (!isSafeHole(level, candidate)) {
                        continue;
                    }

                    double distance = candidate.getCenter().multiply(1.0, 0.0, 1.0)
                            .distanceToSqr(player.getX(), 0.0, player.getZ());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    static boolean isSafeHole(Level level, BlockPos feet) {
        if (!level.getBlockState(feet).isAir()
                || !level.getBlockState(feet.above()).isAir()
                || !isBlastResistantFullBlock(level, feet.below())) {
            return false;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!isBlastResistantFullBlock(level, feet.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlastResistantFullBlock(Level level, BlockPos position) {
        var state = level.getBlockState(position);
        return state.isCollisionShapeFullBlock(level, position)
                && state.getBlock().getExplosionResistance() >= BLAST_RESISTANT_THRESHOLD;
    }
}
