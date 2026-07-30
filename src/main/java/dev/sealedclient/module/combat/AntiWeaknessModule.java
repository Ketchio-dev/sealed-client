package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Objects;

public final class AntiWeaknessModule extends Module implements TickableModule {
    private static final String OWNER = "anti_weakness";
    private static final int PRIORITY = 85;

    private final ActionCoordinator actions;
    private final BooleanSetting restoreSlot = addSetting(new BooleanSetting(
            "restore_slot",
            "Restore slot",
            "Return to the previous slot after the attack key is released.",
            true
    ));
    private int previousSlot = -1;
    private boolean switched;

    public AntiWeaknessModule(ActionCoordinator actions) {
        super(
                "anti_weakness",
                "Anti Weakness",
                "Selects a sword or axe when weakness would prevent a normal melee hit.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!CombatUtil.isReady(minecraft)
                || !minecraft.player.hasEffect(MobEffects.WEAKNESS)
                || !minecraft.options.keyAttack.isDown()
                || !(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity)) {
            restore(minecraft);
            return;
        }
        int best = bestWeapon(minecraft);
        int selected = minecraft.player.getInventory().selected;
        if (best < 0
                || best == selected
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)) {
            return;
        }
        if (!switched) {
            previousSlot = selected;
        }
        minecraft.player.getInventory().setSelectedHotbarSlot(best);
        switched = true;
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        restore(minecraft);
        actions.releaseOwner(minecraft, OWNER);
    }

    private static int bestWeapon(Minecraft minecraft) {
        int best = -1;
        float bestSpeed = -1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof SwordItem)
                    && !(stack.getItem() instanceof AxeItem)) {
                continue;
            }
            float score = stack.getItem() instanceof SwordItem ? 2.0f : 1.0f;
            score += stack.isDamageableItem()
                    ? (float) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage()
                    : 1.0f;
            if (score > bestSpeed) {
                bestSpeed = score;
                best = slot;
            }
        }
        return best;
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
