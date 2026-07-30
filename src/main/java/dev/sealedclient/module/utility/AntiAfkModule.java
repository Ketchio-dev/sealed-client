package dev.sealedclient.module.utility;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.EnumSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

import java.util.Objects;

public final class AntiAfkModule extends Module implements TickableModule {
    private static final String OWNER = "anti_afk";

    private final IntegerSetting intervalSeconds = addSetting(new IntegerSetting(
            "interval_seconds",
            "Interval",
            "Seconds of inactivity before an anti-AFK action.",
            45,
            10,
            300,
            5
    ));
    private final EnumSetting<Action> action = addSetting(new EnumSetting<>(
            "action",
            "Action",
            "The low-impact action used while idle.",
            Action.SWING
    ));
    private final ActionCoordinator actions;
    private int idleTicks;
    private boolean releaseJump;
    private boolean initialized;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;

    public AntiAfkModule(ActionCoordinator actions) {
        super(
                "anti_afk",
                "Anti AFK",
                "Performs a low-impact action after a period of inactivity.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (releaseJump) {
            minecraft.options.keyJump.setDown(false);
            actions.releaseOwner(minecraft, OWNER);
            releaseJump = false;
        }
        if (minecraft.player == null || minecraft.screen != null) {
            reset();
            return;
        }

        if (hasUserActivity(minecraft)) {
            idleTicks = 0;
        } else {
            idleTicks++;
        }
        remember(minecraft);

        if (idleTicks < intervalSeconds.get() * 20) {
            return;
        }
        idleTicks = 0;
        if (action.get() == Action.SWING) {
            if (actions.claim(ActionCoordinator.Channel.ATTACK, OWNER, 5, 1)) {
                minecraft.player.swing(InteractionHand.MAIN_HAND);
            }
        } else if (actions.setKey(
                minecraft,
                ActionCoordinator.Channel.MOVEMENT,
                OWNER,
                5,
                minecraft.options.keyJump,
                true
        )) {
            releaseJump = true;
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        minecraft.options.keyJump.setDown(false);
        actions.releaseOwner(minecraft, OWNER);
        reset();
        releaseJump = false;
    }

    private boolean hasUserActivity(Minecraft minecraft) {
        if (!initialized) {
            return true;
        }
        return Math.abs(minecraft.player.getX() - lastX) > 0.001
                || Math.abs(minecraft.player.getY() - lastY) > 0.001
                || Math.abs(minecraft.player.getZ() - lastZ) > 0.001
                || minecraft.player.getYRot() != lastYaw
                || minecraft.player.getXRot() != lastPitch
                || minecraft.options.keyAttack.isDown()
                || minecraft.options.keyUse.isDown()
                || minecraft.options.keyJump.isDown()
                || minecraft.options.keyShift.isDown();
    }

    private void remember(Minecraft minecraft) {
        initialized = true;
        lastX = minecraft.player.getX();
        lastY = minecraft.player.getY();
        lastZ = minecraft.player.getZ();
        lastYaw = minecraft.player.getYRot();
        lastPitch = minecraft.player.getXRot();
    }

    private void reset() {
        initialized = false;
        idleTicks = 0;
    }

    private enum Action {
        SWING,
        JUMP
    }
}
