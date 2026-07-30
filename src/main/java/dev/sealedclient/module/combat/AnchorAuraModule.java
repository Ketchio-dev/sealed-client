package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Objects;

public final class AnchorAuraModule extends Module implements TickableModule {
    private static final String OWNER = "anchor_aura";
    private static final int PRIORITY = 78;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final DoubleSetting targetRange = addSetting(new DoubleSetting(
            "target_range",
            "Target range",
            "Maximum enemy distance.",
            6.0,
            2.0,
            10.0,
            0.1
    ));
    private final DoubleSetting actionRange = addSetting(new DoubleSetting(
            "action_range",
            "Action range",
            "Maximum distance to an anchor position.",
            4.5,
            2.0,
            6.0,
            0.1
    ));
    private final DoubleSetting minSelfDistance = addSetting(new DoubleSetting(
            "min_self_distance",
            "Self distance",
            "Do not create an explosion closer than this distance.",
            3.5,
            1.0,
            8.0,
            0.1
    ));
    private final DoubleSetting friendSafety = addSetting(new DoubleSetting(
            "friend_safety",
            "Friend safety",
            "Do not use an anchor close to a friend.",
            5.0,
            0.0,
            10.0,
            0.5
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between place, charge, and detonate actions.",
            3,
            1,
            20,
            1
    ));
    private int cooldown;

    public AnchorAuraModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "anchor_aura",
                "Anchor Aura",
                "Places, charges, and activates respawn anchors where anchors explode.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.friends = Objects.requireNonNull(friends, "friends");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (!CombatUtil.isReady(minecraft)
                || cooldown > 0
                || minecraft.level.dimensionType().respawnAnchorWorks()) {
            return;
        }
        Player target = CombatUtil.nearestEnemyPlayer(
                minecraft,
                friends,
                targetRange.get()
        ).orElse(null);
        if (target == null) {
            return;
        }
        BlockPos anchor = findExistingAnchor(minecraft, target);
        boolean acted = anchor != null
                ? interactAnchor(minecraft, anchor)
                : placeAnchor(minecraft, target);
        if (acted) {
            cooldown = delay.get();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private BlockPos findExistingAnchor(Minecraft minecraft, Player target) {
        BlockPos center = target.blockPosition();
        int radius = (int) Math.ceil(actionRange.get());
        double reach = actionRange.get() * actionRange.get();
        return BlockPos.betweenClosedStream(
                        center.offset(-radius, -2, -radius),
                        center.offset(radius, 2, radius)
                )
                .map(BlockPos::immutable)
                .filter(position -> minecraft.level.getBlockState(position)
                        .is(Blocks.RESPAWN_ANCHOR))
                .filter(position -> minecraft.player.getEyePosition()
                        .distanceToSqr(position.getCenter()) <= reach)
                .filter(position -> safeExplosion(minecraft, position.getCenter()))
                .min(Comparator.comparingDouble(position ->
                        position.getCenter().distanceToSqr(target.position())))
                .orElse(null);
    }

    private boolean interactAnchor(Minecraft minecraft, BlockPos anchor) {
        BlockState state = minecraft.level.getBlockState(anchor);
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        int slot = charge == 0
                ? CombatUtil.findHotbarItem(minecraft.player, Items.GLOWSTONE)
                : CombatUtil.findHotbar(
                        minecraft.player,
                        stack -> stack.isEmpty() || isSafeDetonator(stack)
                );
        if (slot < 0
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return false;
        }
        int previous = minecraft.player.getInventory().selected;
        minecraft.player.getInventory().setSelectedHotbarSlot(slot);
        if (actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
            CombatUtil.rotateToward(minecraft.player, anchor.getCenter());
        }
        BlockHitResult hit = new BlockHitResult(
                anchor.getCenter(),
                Direction.UP,
                anchor,
                false
        );
        boolean used = minecraft.gameMode.useItemOn(
                minecraft.player,
                InteractionHand.MAIN_HAND,
                hit
        ).consumesAction();
        if (used) {
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        }
        if (previous != slot) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previous);
        }
        return used;
    }

    private boolean placeAnchor(Minecraft minecraft, Player target) {
        int slot = CombatUtil.findHotbarItem(minecraft.player, Items.RESPAWN_ANCHOR);
        BlockPos position = findPlacement(minecraft, target);
        if (slot < 0
                || position == null
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return false;
        }
        int previous = minecraft.player.getInventory().selected;
        minecraft.player.getInventory().setSelectedHotbarSlot(slot);
        if (actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
            CombatUtil.rotateToward(minecraft.player, position.getCenter());
        }
        boolean placed = CombatUtil.placeBlock(
                minecraft,
                position,
                InteractionHand.MAIN_HAND
        );
        if (previous != slot) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previous);
        }
        return placed;
    }

    private BlockPos findPlacement(Minecraft minecraft, Player target) {
        BlockPos feet = target.blockPosition();
        double reach = actionRange.get() * actionRange.get();
        return java.util.stream.Stream.concat(
                        Direction.Plane.HORIZONTAL.stream()
                                .map(direction -> feet.relative(direction)),
                        java.util.stream.Stream.of(feet.above(2))
                )
                .filter(position -> minecraft.player.getEyePosition()
                        .distanceToSqr(position.getCenter()) <= reach)
                .filter(position -> safeExplosion(minecraft, position.getCenter()))
                .filter(position -> CombatUtil.canPlaceBlock(minecraft, position))
                .findFirst()
                .orElse(null);
    }

    private boolean safeExplosion(Minecraft minecraft, Vec3 position) {
        if (minecraft.player.position().distanceToSqr(position)
                < minSelfDistance.get() * minSelfDistance.get()) {
            return false;
        }
        double friendLimit = friendSafety.get() * friendSafety.get();
        return minecraft.level.players().stream()
                .filter(friends::isFriend)
                .noneMatch(friend -> friend.position().distanceToSqr(position) < friendLimit);
    }

    private static boolean isSafeDetonator(ItemStack stack) {
        return !stack.is(Items.GLOWSTONE)
                && !stack.is(Items.RESPAWN_ANCHOR)
                && !(stack.getItem() instanceof BlockItem);
    }
}
