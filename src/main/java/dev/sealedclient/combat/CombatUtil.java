package dev.sealedclient.combat;

import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.RotationApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;

public final class CombatUtil {
    private CombatUtil() {
    }

    public static boolean isReady(Minecraft minecraft) {
        return minecraft.player != null
                && minecraft.level != null
                && minecraft.gameMode != null
                && minecraft.screen == null;
    }

    public static int findHotbar(LocalPlayer player, Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < 9; slot++) {
            if (predicate.test(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    public static int findInventory(LocalPlayer player, Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < 36; slot++) {
            if (predicate.test(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    public static int findHotbarItem(LocalPlayer player, Item item) {
        return findHotbar(player, stack -> stack.is(item));
    }

    public static Optional<Player> nearestEnemyPlayer(
            Minecraft minecraft,
            FriendManager friends,
            double range
    ) {
        if (minecraft.player == null || minecraft.level == null) {
            return Optional.empty();
        }
        double rangeSquared = range * range;
        return minecraft.level.players().stream()
                .map(Player.class::cast)
                .filter(player -> isAttackablePlayer(minecraft.player, player, friends))
                .filter(player -> minecraft.player.distanceToSqr(player) <= rangeSquared)
                .min(Comparator.comparingDouble(minecraft.player::distanceToSqr));
    }

    public static boolean isAttackablePlayer(
            LocalPlayer self,
            Player player,
            FriendManager friends
    ) {
        return player != self
                && player.isAlive()
                && !player.isSpectator()
                && !player.isCreative()
                && !friends.isFriend(player)
                && self.canAttack(player);
    }

    public static boolean isAttackableLiving(
            LocalPlayer self,
            Entity entity,
            FriendManager friends,
            boolean players,
            boolean hostiles
    ) {
        if (!(entity instanceof LivingEntity living)
                || entity == self
                || !living.isAlive()
                || !self.canAttack(living)) {
            return false;
        }
        if (entity instanceof Player player) {
            return players && isAttackablePlayer(self, player, friends);
        }
        return hostiles && entity instanceof Enemy;
    }

    /**
     * Bids to aim at {@code target} through the shared rotation arbiter.
     *
     * @return {@code true} if this owner won the aim for this tick
     */
    public static boolean rotateToward(
            Minecraft minecraft,
            RotationApplier rotations,
            String owner,
            int priority,
            Vec3 target
    ) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        Vec3 delta = target.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        return rotations.request(minecraft, owner, priority, yaw, pitch);
    }

    public static boolean canPlaceBlock(Minecraft minecraft, BlockPos target) {
        if (minecraft.level == null
                || minecraft.player == null
                || !minecraft.level.isInWorldBounds(target)
                || !minecraft.level.getWorldBorder().isWithinBounds(target)
                || !minecraft.level.getBlockState(target).canBeReplaced()) {
            return false;
        }
        AABB box = new AABB(target).deflate(0.001);
        return minecraft.level.getEntities(
                minecraft.player,
                box,
                entity -> entity.isAlive() && !entity.isRemoved()
        ).isEmpty();
    }

    public static boolean placeBlock(Minecraft minecraft, BlockPos target, InteractionHand hand) {
        if (!canPlaceBlock(minecraft, target)) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.relative(direction);
            BlockState neighborState = minecraft.level.getBlockState(neighbor);
            if (neighborState.canBeReplaced()) {
                continue;
            }
            Direction clickedFace = direction.getOpposite();
            Vec3 hitLocation = neighbor.getCenter().add(
                    clickedFace.getStepX() * 0.5,
                    clickedFace.getStepY() * 0.5,
                    clickedFace.getStepZ() * 0.5
            );
            BlockHitResult hit = new BlockHitResult(
                    hitLocation,
                    clickedFace,
                    neighbor,
                    false
            );
            InteractionResult result = minecraft.gameMode.useItemOn(
                    minecraft.player,
                    hand,
                    hit
            );
            if (result.consumesAction()) {
                minecraft.player.swing(hand);
                return true;
            }
        }
        return false;
    }

    public static boolean isBlastResistant(BlockState state) {
        return !state.isAir() && state.getBlock().getExplosionResistance() >= 600.0f;
    }

    public static boolean isSafeHole(Minecraft minecraft, BlockPos position) {
        if (minecraft.level == null
                || !minecraft.level.getBlockState(position).canBeReplaced()
                || !minecraft.level.getBlockState(position.above()).canBeReplaced()
                || !isBlastResistant(minecraft.level.getBlockState(position.below()))) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!isBlastResistant(minecraft.level.getBlockState(position.relative(direction)))) {
                return false;
            }
        }
        return true;
    }
}
