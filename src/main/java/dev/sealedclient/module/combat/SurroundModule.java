package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.platform.HotbarAccess;
import dev.sealedclient.service.ActionCoordinator;
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

        int previous = HotbarAccess.selectedSlot(minecraft.player);
        HotbarAccess.selectSlot(minecraft.player, obsidian);
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
            HotbarAccess.selectSlot(minecraft.player, previous);
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
