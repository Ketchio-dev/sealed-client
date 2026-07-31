package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.EnumSetting;
import dev.sealedclient.platform.HotbarAccess;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

public final class AutoMineModule extends Module implements TickableModule {
    private static final String OWNER = "auto_mine";
    private static final int PRIORITY = 55;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final EnumSetting<Mode> mode = addSetting(new EnumSetting<>(
            "mode",
            "Mode",
            "Select the block under the crosshair or an enemy surround block.",
            Mode.CROSSHAIR
    ));
    private final DoubleSetting range = addSetting(new DoubleSetting(
            "range",
            "Range",
            "Maximum mining distance.",
            4.5,
            2.0,
            6.0,
            0.1
    ));
    private final DoubleSetting targetRange = addSetting(new DoubleSetting(
            "target_range",
            "Target range",
            "Maximum enemy distance in surround mode.",
            8.0,
            3.0,
            16.0,
            0.5
    ));
    private final BooleanSetting autoTool = addSetting(new BooleanSetting(
            "auto_tool",
            "Auto tool",
            "Select the fastest hotbar tool for the target block.",
            true
    ));
    private final BooleanSetting restoreSlot = addSetting(new BooleanSetting(
            "restore_slot",
            "Restore slot",
            "Restore the selected slot after mining stops.",
            true
    ));

    private BlockPos mining;
    private Direction face = Direction.UP;
    private int previousSlot = -1;

    public AutoMineModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "auto_mine",
                "Auto Mine",
                "Continuously mines a selected block using vanilla destroy actions.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.friends = Objects.requireNonNull(friends, "friends");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!CombatUtil.isReady(minecraft)) {
            stop(minecraft);
            return;
        }
        BlockTarget target = selectTarget(minecraft);
        if (target == null
                || minecraft.player.getEyePosition().distanceToSqr(
                        target.position.getCenter()
                ) > range.get() * range.get()
                || !actions.claim(ActionCoordinator.Channel.ATTACK, OWNER, PRIORITY, 1)) {
            stop(minecraft);
            return;
        }
        if (autoTool.get()
                && actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)) {
            selectTool(minecraft, target.position);
        }
        if (!target.position.equals(mining)) {
            if (mining != null) {
                minecraft.gameMode.stopDestroyBlock();
            }
            mining = target.position.immutable();
            face = target.face;
            minecraft.gameMode.startDestroyBlock(mining, face);
        } else {
            minecraft.gameMode.continueDestroyBlock(mining, face);
        }
        minecraft.player.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        stop(minecraft);
        actions.releaseOwner(minecraft, OWNER);
    }

    private BlockTarget selectTarget(Minecraft minecraft) {
        if (mode.get() == Mode.CROSSHAIR) {
            if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
                return null;
            }
            BlockState state = minecraft.level.getBlockState(hit.getBlockPos());
            return validState(minecraft, hit.getBlockPos(), state)
                    ? new BlockTarget(hit.getBlockPos(), hit.getDirection())
                    : null;
        }
        Player target = CombatUtil.nearestEnemyPlayer(
                minecraft,
                friends,
                targetRange.get()
        ).orElse(null);
        if (target == null) {
            return null;
        }
        BlockPos feet = target.blockPosition();
        return Stream.of(feet.north(), feet.south(), feet.east(), feet.west(), feet.below())
                .filter(position -> validState(
                        minecraft,
                        position,
                        minecraft.level.getBlockState(position)
                ))
                .min(Comparator.comparingDouble(position ->
                        minecraft.player.getEyePosition().distanceToSqr(position.getCenter())))
                .map(position -> new BlockTarget(position, Direction.UP))
                .orElse(null);
    }

    private static boolean validState(
            Minecraft minecraft,
            BlockPos position,
            BlockState state
    ) {
        return !state.isAir()
                && !state.canBeReplaced()
                && state.getDestroySpeed(minecraft.level, position) >= 0.0f;
    }

    private void selectTool(Minecraft minecraft, BlockPos position) {
        BlockState state = minecraft.level.getBlockState(position);
        int selected = HotbarAccess.selectedSlot(minecraft.player);
        int best = selected;
        float bestSpeed = minecraft.player.getInventory().getItem(selected).getDestroySpeed(state);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        if (best != selected) {
            if (previousSlot < 0) {
                previousSlot = selected;
            }
            HotbarAccess.selectSlot(minecraft.player, best);
        }
    }

    private void stop(Minecraft minecraft) {
        if (mining != null && minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
        }
        mining = null;
        if (restoreSlot.get()
                && previousSlot >= 0
                && previousSlot < 9
                && minecraft.player != null) {
            HotbarAccess.selectSlot(minecraft.player, previousSlot);
        }
        previousSlot = -1;
    }

    private record BlockTarget(BlockPos position, Direction face) {
    }

    private enum Mode {
        CROSSHAIR,
        ENEMY_SURROUND
    }
}
