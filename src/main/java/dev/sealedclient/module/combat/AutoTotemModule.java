package dev.sealedclient.module.combat;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.util.InventoryActions;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AutoTotemModule extends Module implements TickableModule {
    private final ActionCoordinator actions;
    private final DoubleSetting health = addSetting(new DoubleSetting(
            "health",
            "Health",
            "Equip a totem at or below this health plus absorption value.",
            20.0,
            1.0,
            36.0,
            0.5
    ));

    private final BooleanSetting replaceOffhand = addSetting(new BooleanSetting(
            "replace_offhand",
            "Replace offhand",
            "Allow replacing a non-totem item in the offhand.",
            true
    ));

    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Minimum ticks between inventory actions.",
            2,
            1,
            20,
            1
    ));

    private int cooldown;

    public AutoTotemModule() {
        this(new ActionCoordinator());
    }

    public AutoTotemModule(ActionCoordinator actions) {
        super(
                "auto_totem",
                "Auto Totem",
                "Moves a totem to the offhand before health becomes critical.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (!InventoryActions.isReady(minecraft)
                || minecraft.player.getHealth() + minecraft.player.getAbsorptionAmount() > health.get()
                || minecraft.player.getItemInHand(InteractionHand.OFF_HAND).is(Items.TOTEM_OF_UNDYING)
                || cooldown > 0) {
            return;
        }

        ItemStack offhand = minecraft.player.getItemInHand(InteractionHand.OFF_HAND);
        if (!offhand.isEmpty() && !replaceOffhand.get()) {
            return;
        }

        int totemSlot = findTotem(minecraft);
        if (totemSlot >= 0 && actions.claim(
                ActionCoordinator.Channel.INVENTORY,
                id(),
                100,
                delay.get()
        )) {
            InventoryActions.swapWithOffhand(minecraft, totemSlot);
            cooldown = delay.get();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, id());
    }

    private static int findTotem(Minecraft minecraft) {
        for (int slot = 0; slot < 36; slot++) {
            if (minecraft.player.getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
                return slot;
            }
        }
        return -1;
    }
}
