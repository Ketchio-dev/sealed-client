package dev.b2tclient.module.combat;

import dev.b2tclient.combat.CombatUtil;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AutoTrapModule extends Module implements TickableModule {
    private static final String OWNER = "auto_trap";
    private static final int PRIORITY = 68;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final DoubleSetting targetRange = addSetting(new DoubleSetting(
            "target_range",
            "Target range",
            "Maximum distance to the enemy being trapped.",
            4.5,
            2.0,
            6.0,
            0.1
    ));
    private final BooleanSetting headSides = addSetting(new BooleanSetting(
            "head_sides",
            "Head sides",
            "Add blocks beside the target's head before placing a roof.",
            false
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between successful placements.",
            2,
            0,
            20,
            1
    ));
    private int cooldown;

    public AutoTrapModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "auto_trap",
                "Auto Trap",
                "Places obsidian around the nearest non-friend player one block at a time.",
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
        int slot = CombatUtil.findHotbarItem(minecraft.player, Items.OBSIDIAN);
        BlockPos placement = target == null ? null : nextPlacement(minecraft, target);
        if (slot < 0
                || placement == null
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return;
        }
        int previous = minecraft.player.getInventory().selected;
        minecraft.player.getInventory().setSelectedHotbarSlot(slot);
        boolean placed = CombatUtil.placeBlock(minecraft, placement, InteractionHand.MAIN_HAND);
        if (previous != slot) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previous);
        }
        if (placed) {
            cooldown = delay.get();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private BlockPos nextPlacement(Minecraft minecraft, Player target) {
        BlockPos feet = target.blockPosition();
        List<BlockPos> positions = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            positions.add(feet.relative(direction));
        }
        if (headSides.get()) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                positions.add(feet.above().relative(direction));
            }
        }
        positions.add(feet.above(2));
        double reachSquared = targetRange.get() * targetRange.get();
        return positions.stream()
                .filter(position -> minecraft.player.getEyePosition()
                        .distanceToSqr(position.getCenter()) <= reachSquared)
                .filter(position -> CombatUtil.canPlaceBlock(minecraft, position))
                .findFirst()
                .orElse(null);
    }
}
