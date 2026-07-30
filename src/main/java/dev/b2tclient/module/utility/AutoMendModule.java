package dev.b2tclient.module.utility;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

/**
 * Uses XP bottles only while the player explicitly holds sneak by default.
 * This gives the automation a deliberate dead-man switch.
 */
public final class AutoMendModule extends Module implements TickableModule {
    private static final String OWNER = "auto_mend";
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private final IntegerSetting startAt = addSetting(new IntegerSetting(
            "start_at",
            "Start at",
            "Mend when any equipped armor is at or below this durability percentage.",
            65,
            5,
            95,
            5
    ));
    private final IntegerSetting stopAt = addSetting(new IntegerSetting(
            "stop_at",
            "Stop at",
            "Stop using XP when every equipped armor piece reaches this percentage.",
            90,
            10,
            100,
            5
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between XP bottles.",
            2,
            1,
            10,
            1
    ));
    private final BooleanSetting requireSneak = addSetting(new BooleanSetting(
            "require_sneak",
            "Require sneak",
            "Only mend while the sneak key is held.",
            true
    ));
    private final ActionCoordinator actions;
    private boolean mending;
    private int previousSlot = -1;
    private float previousPitch;
    private int cooldown;

    public AutoMendModule(ActionCoordinator actions) {
        super(
                "auto_mend",
                "Auto Mend",
                "Uses hotbar XP bottles on damaged equipped armor.",
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
        if (minecraft.player == null
                || minecraft.gameMode == null
                || minecraft.screen != null
                || requireSneak.get() && !minecraft.options.keyShift.isDown()) {
            stop(minecraft);
            return;
        }

        int lowestDurability = lowestArmorDurability(minecraft);
        int effectiveStopAt = Math.max(startAt.get(), stopAt.get());
        if (!mending && lowestDurability > startAt.get()
                || mending && lowestDurability >= effectiveStopAt) {
            stop(minecraft);
            return;
        }

        int bottleSlot = findBottle(minecraft);
        if (bottleSlot < 0) {
            stop(minecraft);
            return;
        }
        if (!mending) {
            previousSlot = minecraft.player.getInventory().selected;
            previousPitch = minecraft.player.getXRot();
            mending = true;
        }
        if (!actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, 50, 1)
                || !actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, 50, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, 50, 1)) {
            stop(minecraft);
            return;
        }

        minecraft.player.getInventory().setSelectedHotbarSlot(bottleSlot);
        minecraft.player.setXRot(90.0f);
        if (cooldown == 0) {
            minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
            cooldown = delay.get();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        stop(minecraft);
        cooldown = 0;
    }

    private void stop(Minecraft minecraft) {
        if (mending && minecraft.player != null) {
            if (previousSlot >= 0 && previousSlot < 9) {
                minecraft.player.getInventory().setSelectedHotbarSlot(previousSlot);
            }
            minecraft.player.setXRot(previousPitch);
        }
        mending = false;
        previousSlot = -1;
        actions.releaseOwner(minecraft, OWNER);
    }

    private static int lowestArmorDurability(Minecraft minecraft) {
        int lowest = 100;
        boolean found = false;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = minecraft.player.getItemBySlot(slot);
            if (!stack.isDamageableItem()) {
                continue;
            }
            found = true;
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            lowest = Math.min(lowest, Math.round(remaining * 100.0f / stack.getMaxDamage()));
        }
        return found ? lowest : 100;
    }

    private static int findBottle(Minecraft minecraft) {
        for (int slot = 0; slot < 9; slot++) {
            if (minecraft.player.getInventory().getItem(slot).is(Items.EXPERIENCE_BOTTLE)) {
                return slot;
            }
        }
        return -1;
    }
}
