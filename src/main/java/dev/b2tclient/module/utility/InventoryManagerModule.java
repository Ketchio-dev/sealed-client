package dev.b2tclient.module.utility;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.util.InventoryActions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Conservative inventory maintenance. It only combines identical stacks when
 * the source can be fully emptied; it never drops, destroys, or guesses which
 * items the user considers trash.
 */
public final class InventoryManagerModule extends Module implements TickableModule {
    private static final String OWNER = "inventory_manager";

    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between stack consolidation actions.",
            8,
            2,
            40,
            1
    ));
    private final ActionCoordinator actions;
    private int cooldown;

    public InventoryManagerModule(ActionCoordinator actions) {
        super(
                "inventory_manager",
                "Inventory Manager",
                "Consolidates identical inventory stacks without dropping items.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (cooldown > 0 || !InventoryActions.isReady(minecraft)) {
            return;
        }

        for (int sourceSlot = 9; sourceSlot < 36; sourceSlot++) {
            ItemStack source = minecraft.player.getInventory().getItem(sourceSlot);
            if (source.isEmpty() || !source.isStackable()) {
                continue;
            }
            for (int targetSlot = 9; targetSlot < sourceSlot; targetSlot++) {
                ItemStack target = minecraft.player.getInventory().getItem(targetSlot);
                if (!ItemStack.isSameItemSameComponents(source, target)
                        || target.getCount() + source.getCount() > target.getMaxStackSize()
                        || !actions.claim(
                                ActionCoordinator.Channel.INVENTORY,
                                OWNER,
                                10,
                                1
                        )) {
                    continue;
                }
                InventoryActions.pickupSwap(minecraft, sourceSlot, targetSlot);
                cooldown = delay.get();
                return;
            }
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }
}
