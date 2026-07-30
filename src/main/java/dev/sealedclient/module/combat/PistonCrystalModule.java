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
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class PistonCrystalModule extends Module implements TickableModule {
    private static final String OWNER = "piston_crystal";
    private static final int PRIORITY = 76;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final DoubleSetting targetRange = addSetting(new DoubleSetting(
            "target_range",
            "Target range",
            "Maximum distance to select an enemy.",
            6.0,
            2.0,
            10.0,
            0.1
    ));
    private final DoubleSetting actionRange = addSetting(new DoubleSetting(
            "action_range",
            "Action range",
            "Maximum reach to each setup block.",
            4.5,
            2.0,
            6.0,
            0.1
    ));
    private final DoubleSetting friendSafety = addSetting(new DoubleSetting(
            "friend_safety",
            "Friend safety",
            "Do not build a setup close to a friend.",
            5.0,
            0.0,
            10.0,
            0.5
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between crystal, piston, and power placement.",
            3,
            1,
            20,
            1
    ));
    private int cooldown;

    public PistonCrystalModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "piston_crystal",
                "Piston Crystal",
                "Builds a rate-limited crystal, piston, and redstone setup beside an enemy.",
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
        if (!CombatUtil.isReady(minecraft) || cooldown > 0) {
            return;
        }
        Player target = CombatUtil.nearestEnemyPlayer(
                minecraft,
                friends,
                targetRange.get()
        ).orElse(null);
        Layout layout = target == null ? null : findLayout(minecraft, target);
        if (layout == null) {
            return;
        }

        boolean acted;
        if (!hasCrystal(minecraft, layout.crystalPosition())) {
            acted = placeCrystal(minecraft, layout);
        } else if (!minecraft.level.getBlockState(layout.piston()).is(Blocks.PISTON)) {
            acted = placeBlock(minecraft, layout, layout.piston(), Items.PISTON, true);
        } else if (!minecraft.level.getBlockState(layout.power()).is(Blocks.REDSTONE_BLOCK)) {
            acted = placeBlock(
                    minecraft,
                    layout,
                    layout.power(),
                    Items.REDSTONE_BLOCK,
                    false
            );
        } else {
            acted = false;
        }
        if (acted) {
            cooldown = delay.get();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private Layout findLayout(Minecraft minecraft, Player target) {
        BlockPos feet = target.blockPosition();
        double reachSquared = actionRange.get() * actionRange.get();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos base = feet.relative(direction);
            BlockPos crystal = base.above();
            BlockPos piston = crystal.relative(direction);
            BlockPos power = piston.above();
            var baseBlock = minecraft.level.getBlockState(base).getBlock();
            if (baseBlock != Blocks.OBSIDIAN && baseBlock != Blocks.BEDROCK) {
                continue;
            }
            if (minecraft.player.getEyePosition().distanceToSqr(base.getCenter()) > reachSquared
                    || minecraft.player.getEyePosition().distanceToSqr(piston.getCenter())
                    > reachSquared
                    || minecraft.player.getEyePosition().distanceToSqr(power.getCenter())
                    > reachSquared
                    || !safeForFriends(minecraft, crystal.getCenter())) {
                continue;
            }
            boolean crystalReady = hasCrystal(minecraft, crystal)
                    || validCrystalSpace(minecraft, crystal);
            boolean pistonReady = minecraft.level.getBlockState(piston).is(Blocks.PISTON)
                    || CombatUtil.canPlaceBlock(minecraft, piston);
            boolean powerReady = minecraft.level.getBlockState(power).is(Blocks.REDSTONE_BLOCK)
                    || CombatUtil.canPlaceBlock(minecraft, power);
            if (crystalReady && pistonReady && powerReady) {
                return new Layout(base, crystal, piston, power, direction);
            }
        }
        return null;
    }

    private boolean placeCrystal(Minecraft minecraft, Layout layout) {
        InteractionHand hand;
        int slot = -1;
        if (minecraft.player.getItemInHand(InteractionHand.OFF_HAND).is(Items.END_CRYSTAL)) {
            hand = InteractionHand.OFF_HAND;
        } else {
            slot = CombatUtil.findHotbarItem(minecraft.player, Items.END_CRYSTAL);
            if (slot < 0) {
                return false;
            }
            hand = InteractionHand.MAIN_HAND;
        }
        if (!actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)
                || (slot >= 0
                && !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1))) {
            return false;
        }
        int previous = minecraft.player.getInventory().selected;
        if (slot >= 0) {
            minecraft.player.getInventory().setSelectedHotbarSlot(slot);
        }
        CombatUtil.rotateToward(minecraft.player, layout.base().getCenter());
        BlockHitResult hit = new BlockHitResult(
                layout.base().getCenter().add(0.0, 0.5, 0.0),
                Direction.UP,
                layout.base(),
                false
        );
        boolean used = minecraft.gameMode.useItemOn(
                minecraft.player,
                hand,
                hit
        ).consumesAction();
        if (used) {
            minecraft.player.swing(hand);
        }
        if (slot >= 0 && previous != slot) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previous);
        }
        return used;
    }

    private boolean placeBlock(
            Minecraft minecraft,
            Layout layout,
            BlockPos position,
            Item item,
            boolean orientPiston
    ) {
        int slot = CombatUtil.findHotbarItem(minecraft.player, item);
        if (slot < 0
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return false;
        }
        int previous = minecraft.player.getInventory().selected;
        minecraft.player.getInventory().setSelectedHotbarSlot(slot);
        if (orientPiston
                && actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
            Direction outward = layout.outward();
            Vec3 outwardLook = position.getCenter().add(
                    outward.getStepX() * 4.0,
                    0.0,
                    outward.getStepZ() * 4.0
            );
            CombatUtil.rotateToward(minecraft.player, outwardLook);
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

    private static boolean hasCrystal(Minecraft minecraft, BlockPos position) {
        return !minecraft.level.getEntitiesOfClass(
                EndCrystal.class,
                new AABB(position).inflate(0.5),
                EndCrystal::isAlive
        ).isEmpty();
    }

    private static boolean validCrystalSpace(Minecraft minecraft, BlockPos position) {
        if (!minecraft.level.getBlockState(position).isAir()
                || !minecraft.level.getBlockState(position.above()).isAir()) {
            return false;
        }
        AABB space = new AABB(position).expandTowards(0.0, 1.0, 0.0);
        return minecraft.level.getEntities(
                minecraft.player,
                space,
                entity -> entity.isAlive() && !entity.isRemoved()
        ).isEmpty();
    }

    private boolean safeForFriends(Minecraft minecraft, Vec3 position) {
        double safetySquared = friendSafety.get() * friendSafety.get();
        return minecraft.level.players().stream()
                .filter(friends::isFriend)
                .noneMatch(friend -> friend.position().distanceToSqr(position) < safetySquared);
    }

    private record Layout(
            BlockPos base,
            BlockPos crystalPosition,
            BlockPos piston,
            BlockPos power,
            Direction outward
    ) {
    }
}
