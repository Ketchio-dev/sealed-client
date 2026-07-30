package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.EnumSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.util.InventoryActions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class OffhandModule extends Module implements TickableModule {
    private static final String OWNER = "offhand";
    private static final int PRIORITY = 90;

    private final ActionCoordinator actions;
    private final EnumSetting<OffhandItem> item = addSetting(new EnumSetting<>(
            "item",
            "Item",
            "Item kept in the offhand while health is safe.",
            OffhandItem.END_CRYSTAL
    ));
    private final BooleanSetting emergencyTotem = addSetting(new BooleanSetting(
            "emergency_totem",
            "Emergency totem",
            "Override the selected item with a totem at low health.",
            true
    ));
    private final DoubleSetting emergencyHealth = addSetting(new DoubleSetting(
            "emergency_health",
            "Emergency health",
            "Health plus absorption at which a totem takes priority.",
            16.0,
            1.0,
            36.0,
            0.5
    ));
    private final BooleanSetting replace = addSetting(new BooleanSetting(
            "replace",
            "Replace",
            "Replace a different item already held in the offhand.",
            true
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between inventory swaps.",
            2,
            1,
            20,
            1
    ));

    private int cooldown;

    public OffhandModule(ActionCoordinator actions) {
        super(
                "offhand",
                "Offhand",
                "Keeps a selected combat item in the offhand with a low-health totem fallback.",
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
        if (!InventoryActions.isReady(minecraft) || cooldown > 0) {
            return;
        }

        Item desired = desiredItem(minecraft);
        ItemStack held = minecraft.player.getItemInHand(InteractionHand.OFF_HAND);
        if (held.is(desired) || (!held.isEmpty() && !replace.get())) {
            return;
        }
        int source = CombatUtil.findInventory(minecraft.player, stack -> stack.is(desired));
        if (source < 0
                || !actions.claim(ActionCoordinator.Channel.INVENTORY, OWNER, PRIORITY, 1)) {
            return;
        }
        InventoryActions.swapWithOffhand(minecraft, source);
        cooldown = delay.get();
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private Item desiredItem(Minecraft minecraft) {
        if (emergencyTotem.get()
                && minecraft.player.getHealth() + minecraft.player.getAbsorptionAmount()
                <= emergencyHealth.get()) {
            return Items.TOTEM_OF_UNDYING;
        }
        return item.get().item;
    }

    private enum OffhandItem {
        END_CRYSTAL(Items.END_CRYSTAL),
        TOTEM(Items.TOTEM_OF_UNDYING),
        GOLDEN_APPLE(Items.ENCHANTED_GOLDEN_APPLE),
        SHIELD(Items.SHIELD);

        private final Item item;

        OffhandItem(Item item) {
            this.item = item;
        }
    }
}
