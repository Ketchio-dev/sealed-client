package dev.b2tclient.module.combat;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.function.BiConsumer;

public final class AutoWeaponModule extends Module implements TickableModule {
    private final ActionCoordinator actions;
    private final BooleanSetting restoreSlot = addSetting(new BooleanSetting(
            "restore_slot",
            "Restore slot",
            "Return to the previous slot after the attack key is released.",
            true
    ));

    private final IntegerSetting minimumDurability = addSetting(new IntegerSetting(
            "minimum_durability",
            "Min durability",
            "Avoid weapons with this many or fewer uses remaining.",
            3,
            0,
            100,
            1
    ));

    private int previousSlot = -1;
    private boolean switched;
    private final WeaponScoreAccumulator scoreAccumulator = new WeaponScoreAccumulator();

    public AutoWeaponModule() {
        this(new ActionCoordinator());
    }

    public AutoWeaponModule(ActionCoordinator actions) {
        super(
                "auto_weapon",
                "Auto Weapon",
                "Selects the strongest safe hotbar weapon for a targeted entity.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null
                || minecraft.screen != null
                || !minecraft.options.keyAttack.isDown()
                || !(minecraft.hitResult instanceof EntityHitResult)) {
            restore(minecraft);
            return;
        }

        int selected = minecraft.player.getInventory().selected;
        int bestSlot = selected;
        double bestScore = score(minecraft.player.getInventory().getItem(selected));
        for (int slot = 0; slot < 9; slot++) {
            if (slot == selected) {
                continue;
            }
            double score = score(minecraft.player.getInventory().getItem(slot));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }

        if (bestSlot != selected && actions.claim(
                ActionCoordinator.Channel.HOTBAR,
                id(),
                55,
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

    private double score(ItemStack stack) {
        if (stack.isDamageableItem()
                && stack.getMaxDamage() - stack.getDamageValue() <= minimumDurability.get()) {
            return -1.0;
        }

        scoreAccumulator.reset();
        stack.forEachModifier(EquipmentSlot.MAINHAND, scoreAccumulator);
        return scoreAccumulator.score();
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

    private static final class WeaponScoreAccumulator
            implements BiConsumer<Holder<Attribute>, AttributeModifier> {
        private double attackDamage;
        private double attackSpeed;

        @Override
        public void accept(Holder<Attribute> attribute, AttributeModifier modifier) {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) {
                attackDamage += modifier.amount();
            } else if (attribute.equals(Attributes.ATTACK_SPEED)) {
                attackSpeed += modifier.amount();
            }
        }

        private void reset() {
            attackDamage = 0.0;
            attackSpeed = 0.0;
        }

        private double score() {
            return attackDamage * 10.0 + attackSpeed;
        }
    }
}
