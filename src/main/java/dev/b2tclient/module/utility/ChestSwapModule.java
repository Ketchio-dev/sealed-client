package dev.b2tclient.module.utility;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.util.InventoryActions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import java.util.Objects;

/**
 * One-shot module: enabling it swaps between an elytra and a chestplate, then
 * immediately disables itself.
 */
public final class ChestSwapModule extends Module implements TickableModule {
    private static final String OWNER = "chest_swap";

    private final IntegerSetting minimumDurability = addSetting(new IntegerSetting(
            "minimum_durability",
            "Min durability",
            "Ignore elytras and chestplates with this many uses or fewer.",
            10,
            0,
            100,
            1
    ));
    private final ActionCoordinator actions;

    public ChestSwapModule(ActionCoordinator actions) {
        super(
                "chest_swap",
                "Chest Swap",
                "Swaps between an elytra and a safe chestplate.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!InventoryActions.isReady(minecraft)) {
            return;
        }

        boolean wearingElytra = minecraft.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        int replacement = wearingElytra
                ? findBestChestplate(minecraft)
                : findSafeElytra(minecraft);
        if (replacement >= 0
                && actions.claim(ActionCoordinator.Channel.INVENTORY, OWNER, 60, 1)) {
            InventoryActions.pickupSwap(minecraft, replacement, 6);
        }
        setEnabled(false, minecraft);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        actions.releaseOwner(minecraft, OWNER);
    }

    private int findSafeElytra(Minecraft minecraft) {
        int best = -1;
        int bestRemaining = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            int remaining = remaining(stack);
            if (stack.is(Items.ELYTRA)
                    && remaining > minimumDurability.get()
                    && remaining > bestRemaining) {
                best = slot;
                bestRemaining = remaining;
            }
        }
        return best;
    }

    private int findBestChestplate(Minecraft minecraft) {
        int best = -1;
        int bestScore = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            int durability = remaining(stack);
            if (stack.is(Items.ELYTRA)
                    || equippable == null
                    || equippable.slot() != EquipmentSlot.CHEST
                    || durability <= minimumDurability.get()) {
                continue;
            }
            int score = durability + stack.getMaxDamage() * 10;
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        return best;
    }

    private static int remaining(ItemStack stack) {
        return stack.isDamageableItem()
                ? stack.getMaxDamage() - stack.getDamageValue()
                : Integer.MAX_VALUE;
    }
}
