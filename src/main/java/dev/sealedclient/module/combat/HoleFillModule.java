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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.Objects;

public final class HoleFillModule extends Module implements TickableModule {
    private static final String OWNER = "hole_fill";
    private static final int PRIORITY = 65;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final DoubleSetting targetRange = addSetting(new DoubleSetting(
            "target_range",
            "Target range",
            "Maximum enemy distance used to prioritize holes.",
            8.0,
            2.0,
            16.0,
            0.5
    ));
    private final IntegerSetting scanRadius = addSetting(new IntegerSetting(
            "scan_radius",
            "Scan radius",
            "Horizontal radius around the player to inspect.",
            4,
            1,
            6,
            1
    ));
    private final DoubleSetting enemyRadius = addSetting(new DoubleSetting(
            "enemy_radius",
            "Enemy radius",
            "Only fill holes close to the selected enemy.",
            3.0,
            1.0,
            6.0,
            0.5
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

    public HoleFillModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "hole_fill",
                "Hole Fill",
                "Fills blast-resistant holes near an enemy with obsidian.",
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
        if (target == null) {
            return;
        }
        int obsidian = CombatUtil.findHotbarItem(minecraft.player, Items.OBSIDIAN);
        BlockPos hole = findHole(minecraft, target);
        if (obsidian < 0
                || hole == null
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return;
        }
        int previous = minecraft.player.getInventory().selected;
        minecraft.player.getInventory().setSelectedHotbarSlot(obsidian);
        boolean placed = CombatUtil.placeBlock(minecraft, hole, InteractionHand.MAIN_HAND);
        if (previous != obsidian) {
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

    private BlockPos findHole(Minecraft minecraft, Player target) {
        BlockPos origin = minecraft.player.blockPosition();
        int radius = scanRadius.get();
        double enemyLimit = enemyRadius.get() * enemyRadius.get();
        return BlockPos.betweenClosedStream(
                        origin.offset(-radius, -1, -radius),
                        origin.offset(radius, 1, radius)
                )
                .map(BlockPos::immutable)
                .filter(position -> position.getCenter().distanceToSqr(
                        minecraft.player.position()
                ) <= radius * radius + 2.0)
                .filter(position -> position.getCenter().distanceToSqr(
                        target.position()
                ) <= enemyLimit)
                .filter(position -> CombatUtil.isSafeHole(minecraft, position))
                .filter(position -> CombatUtil.canPlaceBlock(minecraft, position))
                .min(Comparator.comparingDouble(position ->
                        position.getCenter().distanceToSqr(target.position())))
                .orElse(null);
    }
}
