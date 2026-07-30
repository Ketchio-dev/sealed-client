package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.util.InventoryActions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

public final class ElytraSwapModule extends Module implements TickableModule {
    private static final int PRIORITY = 70;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting fallDistance = addSetting(new DoubleSetting(
            "fall_distance",
            "Fall distance",
            "Fall distance before equipping an available elytra.",
            1.5,
            0.5,
            8.0,
            0.1
    ));
    private final IntegerSetting minimumDurability = addSetting(new IntegerSetting(
            "minimum_durability",
            "Min durability",
            "Required remaining elytra durability.",
            8,
            2,
            100,
            1
    ));
    private final BooleanSetting restoreArmor = addSetting(new BooleanSetting(
            "restore_armor",
            "Restore armor",
            "Restore the displaced chest item after landing.",
            true
    ));

    private int restoreSlot = -1;
    private boolean restoreHadArmor;
    private int cooldown;
    private Object activeContext;

    public ElytraSwapModule(ActionCoordinator actions) {
        super(
                "elytra_swap",
                "Elytra Swap",
                "Equips a healthy elytra while falling and restores displaced chest armor.",
                Category.MOVEMENT,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        MovementSafetyController.Observation observation =
                B2TMovementSupport.safetyObservation(minecraft);
        if (!java.util.Objects.equals(activeContext, observation.context())) {
            activeContext = observation.context();
            clearRestoreState();
            cooldown = 0;
        }
        MovementSafetyController.Decision decision = safety.observe(observation);
        if (decision.state() != MovementSafetyController.State.ACTIVE) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        if (!InventoryActions.isReady(minecraft)) {
            actions.releaseOwner(minecraft, id());
            return;
        }

        ItemStack chest = minecraft.player.getItemBySlot(EquipmentSlot.CHEST);
        if (restoreSlot >= 0
                && restoreArmor.get()
                && minecraft.player.onGround()
                && cooldown == 0) {
            restoreChest(minecraft);
            return;
        }
        if (chest.is(Items.ELYTRA)
                || minecraft.player.onGround()
                || minecraft.player.isInWater()
                || minecraft.player.fallDistance < fallDistance.get()
                || cooldown > 0) {
            return;
        }

        int elytraSlot = findUsableElytra(minecraft);
        if (elytraSlot < 0
                || !actions.claim(ActionCoordinator.Channel.INVENTORY, id(), PRIORITY, 2)) {
            return;
        }

        restoreSlot = elytraSlot;
        restoreHadArmor = !chest.isEmpty();
        InventoryActions.pickupSwap(minecraft, elytraSlot, 6);
        cooldown = 8;
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        restoreSlot = -1;
        restoreHadArmor = false;
        cooldown = 0;
        activeContext = null;
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }

    private int findUsableElytra(Minecraft minecraft) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            if (stack.is(Items.ELYTRA)
                    && (!stack.isDamageableItem()
                    || stack.getMaxDamage() - stack.getDamageValue() > minimumDurability.get())) {
                return slot;
            }
        }
        return -1;
    }

    private void restoreChest(Minecraft minecraft) {
        if (!restoreHadArmor) {
            clearRestoreState();
            return;
        }

        ItemStack displaced = minecraft.player.getInventory().getItem(restoreSlot);
        Equippable equippable = displaced.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) {
            clearRestoreState();
            return;
        }
        if (!actions.claim(ActionCoordinator.Channel.INVENTORY, id(), PRIORITY, 2)) {
            return;
        }
        InventoryActions.pickupSwap(minecraft, restoreSlot, 6);
        cooldown = 8;
        clearRestoreState();
    }

    private void clearRestoreState() {
        restoreSlot = -1;
        restoreHadArmor = false;
    }
}
