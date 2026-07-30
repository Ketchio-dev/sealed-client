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
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Tops up partially depleted hotbar stacks without changing item types or
 * discarding inventory contents.
 */
public final class ReplenishModule extends Module implements TickableModule {
    private static final String OWNER = "replenish";

    private final IntegerSetting threshold = addSetting(new IntegerSetting(
            "threshold",
            "Threshold",
            "Top up a hotbar stack when it reaches this count or less.",
            16,
            1,
            63,
            1
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between inventory actions.",
            4,
            1,
            20,
            1
    ));
    private final ActionCoordinator actions;
    private int cooldown;

    public ReplenishModule(ActionCoordinator actions) {
        super(
                "replenish",
                "Replenish",
                "Safely refills matching hotbar stacks from the main inventory.",
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

        for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
            ItemStack target = minecraft.player.getInventory().getItem(hotbarSlot);
            if (target.isEmpty()
                    || !target.isStackable()
                    || target.getCount() > threshold.get()
                    || target.getCount() >= target.getMaxStackSize()) {
                continue;
            }

            int sourceSlot = findSource(minecraft, target);
            if (sourceSlot < 0
                    || !actions.claim(ActionCoordinator.Channel.INVENTORY, OWNER, 30, 1)) {
                continue;
            }

            InventoryActions.pickupSwap(minecraft, sourceSlot, 36 + hotbarSlot);
            cooldown = delay.get();
            return;
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private static int findSource(Minecraft minecraft, ItemStack target) {
        return largestMatchingSlot(
                9,
                36,
                slot -> {
                    ItemStack candidate = minecraft.player.getInventory().getItem(slot);
                    return !candidate.isEmpty()
                            && ItemStack.isSameItemSameComponents(target, candidate);
                },
                slot -> minecraft.player.getInventory().getItem(slot).getCount()
        );
    }

    static int largestMatchingSlot(
            int firstInclusive,
            int lastExclusive,
            IntPredicate matches,
            IntUnaryOperator count
    ) {
        Objects.requireNonNull(matches, "matches");
        Objects.requireNonNull(count, "count");
        int bestSlot = -1;
        int bestCount = -1;
        for (int slot = firstInclusive; slot < lastExclusive; slot++) {
            if (!matches.test(slot)) {
                continue;
            }
            int candidateCount = count.applyAsInt(slot);
            if (candidateCount > bestCount) {
                bestSlot = slot;
                bestCount = candidateCount;
            }
        }
        return bestSlot;
    }
}
