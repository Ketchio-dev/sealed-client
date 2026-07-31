package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.platform.HotbarAccess;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class BurrowModule extends Module implements TickableModule {
    private static final String OWNER = "burrow";
    private static final int PRIORITY = 82;

    private final ActionCoordinator actions;
    private final BooleanSetting autoJump = addSetting(new BooleanSetting(
            "auto_jump",
            "Auto jump",
            "Use a normal jump before attempting the feet placement.",
            true
    ));
    private final IntegerSetting timeout = addSetting(new IntegerSetting(
            "timeout",
            "Timeout",
            "Maximum ticks to wait for a valid vanilla placement.",
            12,
            4,
            30,
            1
    ));
    private BlockPos start;
    private int elapsed;
    private boolean jumped;

    public BurrowModule(ActionCoordinator actions) {
        super(
                "burrow",
                "Burrow",
                "Uses a normal jump and block interaction to place obsidian at the starting feet block.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!CombatUtil.isReady(minecraft)) {
            reset();
            return;
        }
        if (start == null) {
            if (!minecraft.player.onGround()
                    || !minecraft.level.getBlockState(minecraft.player.blockPosition()).canBeReplaced()) {
                return;
            }
            start = minecraft.player.blockPosition().immutable();
            elapsed = 0;
        }
        elapsed++;
        if (elapsed > timeout.get()) {
            reset();
            return;
        }
        if (!jumped && autoJump.get()) {
            if (!actions.claim(ActionCoordinator.Channel.MOVEMENT, OWNER, PRIORITY, 2)) {
                return;
            }
            minecraft.player.jumpFromGround();
            jumped = true;
            return;
        }
        if (minecraft.player.getY() < start.getY() + 1.0) {
            return;
        }
        int slot = CombatUtil.findHotbarItem(minecraft.player, Items.OBSIDIAN);
        if (slot < 0
                || !CombatUtil.canPlaceBlock(minecraft, start)
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return;
        }
        int previous = HotbarAccess.selectedSlot(minecraft.player);
        HotbarAccess.selectSlot(minecraft.player, slot);
        boolean placed = CombatUtil.placeBlock(minecraft, start, InteractionHand.MAIN_HAND);
        if (previous != slot) {
            HotbarAccess.selectSlot(minecraft.player, previous);
        }
        if (placed) {
            reset();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        reset();
        actions.releaseOwner(minecraft, OWNER);
    }

    private void reset() {
        start = null;
        elapsed = 0;
        jumped = false;
    }
}
