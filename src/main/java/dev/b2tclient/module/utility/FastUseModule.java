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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

/**
 * Repeats immediate-use items through the normal game-mode API. Long-use food,
 * bows, shields, and block placement are deliberately excluded.
 */
public final class FastUseModule extends Module implements TickableModule {
    private static final String OWNER = "fast_use";

    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between repeated uses.",
            2,
            1,
            10,
            1
    ));
    private final BooleanSetting experienceBottles = addSetting(new BooleanSetting(
            "experience_bottles",
            "XP bottles",
            "Repeat experience bottle use.",
            true
    ));
    private final BooleanSetting projectiles = addSetting(new BooleanSetting(
            "projectiles",
            "Eggs and snowballs",
            "Repeat egg and snowball use.",
            false
    ));
    private final BooleanSetting pearls = addSetting(new BooleanSetting(
            "pearls",
            "Ender pearls",
            "Repeat ender pearl use; the server cooldown still applies.",
            false
    ));
    private final BooleanSetting fireworks = addSetting(new BooleanSetting(
            "fireworks",
            "Fireworks",
            "Repeat firework use while gliding.",
            false
    ));
    private final ActionCoordinator actions;
    private int cooldown;

    public FastUseModule(ActionCoordinator actions) {
        super(
                "fast_use",
                "Fast Use",
                "Repeats selected immediate-use items at a controlled interval.",
                Category.UTILITY,
                false,
                ModuleRisk.PACKET
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (cooldown > 0
                || minecraft.player == null
                || minecraft.gameMode == null
                || minecraft.screen != null
                || !minecraft.options.keyUse.isDown()
                || minecraft.player.isUsingItem()) {
            return;
        }

        ItemStack stack = minecraft.player.getMainHandItem();
        if (!supported(stack, minecraft.player.isFallFlying())
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, 20, 1)) {
            return;
        }
        minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        cooldown = delay.get();
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private boolean supported(ItemStack stack, boolean fallFlying) {
        return experienceBottles.get() && stack.is(Items.EXPERIENCE_BOTTLE)
                || projectiles.get() && (stack.is(Items.EGG) || stack.is(Items.SNOWBALL))
                || pearls.get() && stack.is(Items.ENDER_PEARL)
                || fireworks.get() && fallFlying && stack.is(Items.FIREWORK_ROCKET);
    }
}
