package dev.sealedclient.module.utility;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class AutoToolModule extends Module implements TickableModule {
    private final ActionCoordinator actions;
    private final BooleanSetting restoreSlot = addSetting(new BooleanSetting(
            "restore_slot",
            "Restore slot",
            "Return to the previous hotbar slot after mining.",
            true
    ));

    private final IntegerSetting minimumDurability = addSetting(new IntegerSetting(
            "minimum_durability",
            "Min durability",
            "Avoid tools with this many or fewer uses remaining.",
            5,
            0,
            100,
            1
    ));

    private int previousSlot = -1;
    private boolean switched;

    public AutoToolModule() {
        this(new ActionCoordinator());
    }

    public AutoToolModule(ActionCoordinator actions) {
        super(
                "auto_tool",
                "Auto Tool",
                "Selects the best safe hotbar tool for the targeted block.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null
                || minecraft.level == null
                || minecraft.screen != null
                || !minecraft.options.keyAttack.isDown()
                || !(minecraft.hitResult instanceof BlockHitResult hitResult)) {
            restore(minecraft);
            return;
        }

        BlockPos position = hitResult.getBlockPos();
        BlockState state = minecraft.level.getBlockState(position);
        int bestSlot = bestSlot(minecraft, state);
        int selected = minecraft.player.getInventory().selected;
        if (bestSlot >= 0 && bestSlot != selected && actions.claim(
                ActionCoordinator.Channel.HOTBAR,
                id(),
                35,
                1
        )) {
            if (!switched) {
                previousSlot = selected;
            }
            minecraft.player.getInventory().setSelectedHotbarSlot(bestSlot);
            switched = true;
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        restore(minecraft);
        actions.releaseOwner(minecraft, id());
    }

    private int bestSlot(Minecraft minecraft, BlockState state) {
        int selected = minecraft.player.getInventory().selected;
        int bestSlot = selected;
        double bestScore = score(minecraft.player.getInventory().getItem(selected), state);

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            double score = score(stack, state);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private double score(ItemStack stack, BlockState state) {
        if (stack.isDamageableItem()
                && stack.getMaxDamage() - stack.getDamageValue() <= minimumDurability.get()) {
            return -1.0;
        }
        return (stack.isCorrectToolForDrops(state) ? 1000.0 : 0.0)
                + stack.getDestroySpeed(state);
    }

    private void restore(Minecraft minecraft) {
        if (switched
                && restoreSlot.get()
                && minecraft.player != null
                && previousSlot >= 0
                && previousSlot < 9) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previousSlot);
        }
        switched = false;
        previousSlot = -1;
    }
}
