package dev.sealedclient.module.utility;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AutoEatModule extends Module implements TickableModule {
    private final ActionCoordinator actions;
    private final IntegerSetting hunger = addSetting(new IntegerSetting(
            "hunger",
            "Hunger",
            "Start eating at or below this food level.",
            14,
            1,
            19,
            1
    ));

    private final BooleanSetting safeFood = addSetting(new BooleanSetting(
            "safe_food",
            "Safe food",
            "Avoid food with harmful or teleporting side effects.",
            true
    ));

    private boolean eating;
    private int previousSlot = -1;

    public AutoEatModule() {
        this(new ActionCoordinator());
    }

    public AutoEatModule(ActionCoordinator actions) {
        super(
                "auto_eat",
                "Auto Eat",
                "Selects food from the hotbar and eats when hungry.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null
                || minecraft.screen != null
                || minecraft.player.getFoodData().getFoodLevel() > hunger.get()) {
            stopEating(minecraft);
            return;
        }

        int slot = bestFoodSlot(minecraft);
        if (slot < 0) {
            stopEating(minecraft);
            return;
        }

        if (!actions.claim(ActionCoordinator.Channel.HOTBAR, id(), 70, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, id(), 70, 1)) {
            stopEating(minecraft);
            return;
        }
        if (!eating) {
            previousSlot = minecraft.player.getInventory().selected;
        }
        minecraft.player.getInventory().setSelectedHotbarSlot(slot);
        minecraft.options.keyUse.setDown(true);
        eating = true;
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        stopEating(minecraft);
        actions.releaseOwner(minecraft, id());
    }

    private int bestFoodSlot(Minecraft minecraft) {
        int bestSlot = -1;
        int bestNutrition = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || safeFood.get() && isUnsafe(stack)) {
                continue;
            }
            if (food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private void stopEating(Minecraft minecraft) {
        if (!eating) {
            return;
        }
        minecraft.options.keyUse.setDown(false);
        if (minecraft.player != null && previousSlot >= 0 && previousSlot < 9) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previousSlot);
        }
        eating = false;
        previousSlot = -1;
    }

    private static boolean isUnsafe(ItemStack stack) {
        return stack.is(Items.ROTTEN_FLESH)
                || stack.is(Items.SPIDER_EYE)
                || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.PUFFERFISH)
                || stack.is(Items.CHORUS_FRUIT);
    }
}
