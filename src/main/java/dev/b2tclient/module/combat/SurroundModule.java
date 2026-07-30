package dev.b2tclient.module.combat;

import dev.b2tclient.combat.CombatUtil;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class SurroundModule extends Module implements TickableModule {
    private static final String OWNER = "surround";
    private static final int PRIORITY = 70;

    private final ActionCoordinator actions;
    private final IntegerSetting blocksPerTick = addSetting(new IntegerSetting(
            "blocks_per_tick",
            "Blocks per tick",
            "Maximum successful placements each tick.",
            1,
            1,
            4,
            1
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between placement passes.",
            1,
            0,
            10,
            1
    ));
    private final BooleanSetting floor = addSetting(new BooleanSetting(
            "floor",
            "Floor",
            "Also fill a missing block directly below the player.",
            true
    ));
    private final BooleanSetting restoreSlot = addSetting(new BooleanSetting(
            "restore_slot",
            "Restore slot",
            "Restore the selected slot after placing.",
            true
    ));
    private int cooldown;

    public SurroundModule(ActionCoordinator actions) {
        super(
                "surround",
                "Surround",
                "Places obsidian around the player's feet using normal block interactions.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (!CombatUtil.isReady(minecraft)
                || cooldown > 0
                || !minecraft.player.onGround()) {
            return;
        }
        int obsidian = CombatUtil.findHotbarItem(minecraft.player, Items.OBSIDIAN);
        if (obsidian < 0
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return;
        }

        int previous = minecraft.player.getInventory().selected;
        minecraft.player.getInventory().setSelectedHotbarSlot(obsidian);
        BlockPos feet = minecraft.player.blockPosition();
        int placed = 0;
        if (floor.get() && CombatUtil.placeBlock(minecraft, feet.below(), InteractionHand.MAIN_HAND)) {
            placed++;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (placed >= blocksPerTick.get()) {
                break;
            }
            if (CombatUtil.placeBlock(
                    minecraft,
                    feet.relative(direction),
                    InteractionHand.MAIN_HAND
            )) {
                placed++;
            }
        }
        if (restoreSlot.get() && previous != obsidian) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previous);
        }
        if (placed > 0) {
            cooldown = delay.get();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }
}
