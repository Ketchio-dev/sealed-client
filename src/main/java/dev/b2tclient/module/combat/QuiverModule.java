package dev.b2tclient.module.combat;

import dev.b2tclient.combat.CombatUtil;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class QuiverModule extends Module implements TickableModule {
    private static final String OWNER = "quiver";
    private static final int PRIORITY = 64;

    private final ActionCoordinator actions;
    private final IntegerSetting drawTicks = addSetting(new IntegerSetting(
            "draw_ticks",
            "Draw ticks",
            "Bow draw duration before releasing upward.",
            20,
            5,
            30,
            1
    ));
    private final DoubleSetting minimumHealth = addSetting(new DoubleSetting(
            "minimum_health",
            "Minimum health",
            "Do not fire a returning arrow below this health plus absorption.",
            16.0,
            1.0,
            36.0,
            0.5
    ));
    private final BooleanSetting repeat = addSetting(new BooleanSetting(
            "repeat",
            "Repeat",
            "Fire another offhand tipped arrow after the repeat delay.",
            false
    ));
    private final IntegerSetting repeatDelay = addSetting(new IntegerSetting(
            "repeat_delay",
            "Repeat delay",
            "Ticks to wait before a repeated shot.",
            100,
            20,
            400,
            10
    ));

    private int previousSlot = -1;
    private int cooldown;
    private boolean started;
    private boolean fired;

    public QuiverModule(ActionCoordinator actions) {
        super(
                "quiver",
                "Quiver",
                "Fires an offhand tipped arrow straight upward so it can return to the player.",
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
        if (!CombatUtil.isReady(minecraft)
                || cooldown > 0
                || (!repeat.get() && fired)
                || minecraft.player.getHealth() + minecraft.player.getAbsorptionAmount()
                < minimumHealth.get()
                || !minecraft.player.getItemInHand(InteractionHand.OFF_HAND)
                .is(Items.TIPPED_ARROW)) {
            return;
        }

        if (!started) {
            int bow = CombatUtil.findHotbar(
                    minecraft.player,
                    stack -> stack.getItem() instanceof BowItem
            );
            if (bow < 0
                    || minecraft.player.isUsingItem()
                    || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 2)
                    || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 2)) {
                return;
            }
            previousSlot = minecraft.player.getInventory().selected;
            minecraft.player.getInventory().setSelectedHotbarSlot(bow);
            minecraft.player.setYRot(minecraft.player.getYRot());
            minecraft.player.setXRot(-90.0f);
            if (minecraft.gameMode.useItem(
                    minecraft.player,
                    InteractionHand.MAIN_HAND
            ).consumesAction()) {
                started = true;
            } else {
                restore(minecraft);
            }
            return;
        }

        if (!minecraft.player.isUsingItem()
                || !(minecraft.player.getUseItem().getItem() instanceof BowItem)) {
            restore(minecraft);
            return;
        }
        if (actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
            minecraft.player.setXRot(-90.0f);
        }
        if (minecraft.player.getTicksUsingItem() < drawTicks.get()
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return;
        }
        minecraft.gameMode.releaseUsingItem(minecraft.player);
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        fired = true;
        cooldown = repeat.get() ? repeatDelay.get() : 0;
        restore(minecraft);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        if (minecraft.player != null && started && minecraft.player.isUsingItem()) {
            minecraft.gameMode.releaseUsingItem(minecraft.player);
        }
        restore(minecraft);
        fired = false;
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private void restore(Minecraft minecraft) {
        if (minecraft.player != null && previousSlot >= 0 && previousSlot < 9) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previousSlot);
        }
        previousSlot = -1;
        started = false;
    }
}
