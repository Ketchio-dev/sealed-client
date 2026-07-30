package dev.sealedclient.module.utility;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.util.InventoryActions;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import java.util.function.BiConsumer;

public final class AutoArmorModule extends Module implements TickableModule {
    private final ActionCoordinator actions;
    private final BooleanSetting preserveElytra = addSetting(new BooleanSetting(
            "preserve_elytra",
            "Preserve elytra",
            "Do not replace an equipped elytra with a chestplate.",
            true
    ));

    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between armor inventory actions.",
            4,
            1,
            20,
            1
    ));

    private int cooldown;
    private final ArmorScoreAccumulator scoreAccumulator = new ArmorScoreAccumulator();
    private final double[] bestScores = new double[ARMOR_SLOTS.length];
    private final int[] bestSlots = new int[ARMOR_SLOTS.length];

    public AutoArmorModule() {
        this(new ActionCoordinator());
    }

    public AutoArmorModule(ActionCoordinator actions) {
        super(
                "auto_armor",
                "Auto Armor",
                "Equips stronger armor from the main inventory.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (cooldown > 0 || !InventoryActions.isReady(minecraft)) {
            return;
        }

        findUpgrades(minecraft);
        for (int index = 0; index < ARMOR_SLOTS.length; index++) {
            if (bestSlots[index] >= 0 && actions.claim(
                    ActionCoordinator.Channel.INVENTORY,
                    id(),
                    25,
                    delay.get()
            )) {
                InventoryActions.pickupSwap(
                        minecraft,
                        bestSlots[index],
                        menuSlot(ARMOR_SLOTS[index])
                );
                cooldown = delay.get();
                return;
            }
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, id());
    }

    private void findUpgrades(Minecraft minecraft) {
        for (int index = 0; index < ARMOR_SLOTS.length; index++) {
            EquipmentSlot equipmentSlot = ARMOR_SLOTS[index];
            bestSlots[index] = -1;
            if (equipmentSlot == EquipmentSlot.CHEST
                    && preserveElytra.get()
                    && minecraft.player.getItemBySlot(equipmentSlot).is(Items.ELYTRA)) {
                bestScores[index] = Double.POSITIVE_INFINITY;
            } else {
                bestScores[index] = score(
                        minecraft.player.getItemBySlot(equipmentSlot),
                        equipmentSlot
                );
            }
        }

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable == null || stack.is(Items.ELYTRA)) {
                continue;
            }

            int index = armorIndex(equippable.slot());
            if (index < 0 || bestScores[index] == Double.POSITIVE_INFINITY) {
                continue;
            }
            double candidateScore = score(stack, equippable.slot());
            if (candidateScore > bestScores[index] + 0.001) {
                bestScores[index] = candidateScore;
                bestSlots[index] = slot;
            }
        }
    }

    private double score(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return -1.0;
        }

        scoreAccumulator.reset();
        stack.forEachModifier(slot, scoreAccumulator);

        if (scoreAccumulator.armor <= 0.0 && scoreAccumulator.toughness <= 0.0) {
            return -1.0;
        }
        double durability = stack.isDamageableItem()
                ? (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage()
                : 1.0;
        return scoreAccumulator.armor * 1000.0
                + scoreAccumulator.toughness * 100.0
                + durability;
    }

    private static int menuSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> throw new IllegalArgumentException("Not an armor slot: " + slot);
        };
    }

    private static int armorIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private static final class ArmorScoreAccumulator
            implements BiConsumer<Holder<Attribute>, AttributeModifier> {
        private double armor;
        private double toughness;

        @Override
        public void accept(Holder<Attribute> attribute, AttributeModifier modifier) {
            if (attribute.equals(Attributes.ARMOR)) {
                armor += modifier.amount();
            } else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) {
                toughness += modifier.amount();
            }
        }

        private void reset() {
            armor = 0.0;
            toughness = 0.0;
        }
    }
}
