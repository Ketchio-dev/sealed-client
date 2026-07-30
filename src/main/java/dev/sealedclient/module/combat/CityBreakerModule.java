package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

public final class CityBreakerModule extends Module implements TickableModule {
    private static final String OWNER = "city_breaker";
    private static final int PRIORITY = 74;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final DoubleSetting targetRange = addSetting(new DoubleSetting(
            "target_range",
            "Target range",
            "Maximum distance to the enemy.",
            6.0,
            2.0,
            10.0,
            0.1
    ));
    private final DoubleSetting mineRange = addSetting(new DoubleSetting(
            "mine_range",
            "Mine range",
            "Maximum distance to the selected surround block.",
            4.5,
            2.0,
            6.0,
            0.1
    ));
    private final BooleanSetting autoTool = addSetting(new BooleanSetting(
            "auto_tool",
            "Auto tool",
            "Select the fastest pickaxe in the hotbar.",
            true
    ));

    private BlockPos mining;
    private int previousSlot = -1;

    public CityBreakerModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "city_breaker",
                "City Breaker",
                "Mines the most reachable block in a non-friend player's surround.",
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
        Player target = CombatUtil.nearestEnemyPlayer(
                minecraft,
                friends,
                targetRange.get()
        ).orElse(null);
        BlockPos block = target == null ? null : findCityBlock(minecraft, target);
        if (block == null
                || minecraft.player.getEyePosition().distanceToSqr(block.getCenter())
                > mineRange.get() * mineRange.get()
                || !actions.claim(ActionCoordinator.Channel.ATTACK, OWNER, PRIORITY, 1)) {
            stop(minecraft);
            return;
        }
        if (autoTool.get()
                && actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)) {
            selectPickaxe(minecraft, block);
        }
        if (!block.equals(mining)) {
            if (mining != null) {
                minecraft.gameMode.stopDestroyBlock();
            }
            mining = block.immutable();
            minecraft.gameMode.startDestroyBlock(mining, Direction.UP);
        } else {
            minecraft.gameMode.continueDestroyBlock(mining, Direction.UP);
        }
        minecraft.player.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        stop(minecraft);
        actions.releaseOwner(minecraft, OWNER);
    }

    private BlockPos findCityBlock(Minecraft minecraft, Player target) {
        BlockPos feet = target.blockPosition();
        return Stream.of(feet.north(), feet.south(), feet.east(), feet.west())
                .filter(position -> {
                    BlockState state = minecraft.level.getBlockState(position);
                    return !state.isAir()
                            && !state.canBeReplaced()
                            && state.getDestroySpeed(minecraft.level, position) >= 0.0f;
                })
                .min(Comparator.comparingDouble(position ->
                        minecraft.player.getEyePosition().distanceToSqr(position.getCenter())))
                .orElse(null);
    }

    private void selectPickaxe(Minecraft minecraft, BlockPos position) {
        BlockState state = minecraft.level.getBlockState(position);
        int selected = minecraft.player.getInventory().selected;
        int best = -1;
        float speed = -1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof PickaxeItem)) {
                continue;
            }
            float candidate = stack.getDestroySpeed(state);
            if (candidate > speed) {
                speed = candidate;
                best = slot;
            }
        }
        if (best >= 0 && best != selected) {
            if (previousSlot < 0) {
                previousSlot = selected;
            }
            minecraft.player.getInventory().setSelectedHotbarSlot(best);
        }
    }

    private void stop(Minecraft minecraft) {
        if (mining != null && minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
        }
        mining = null;
        if (minecraft.player != null && previousSlot >= 0 && previousSlot < 9) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previousSlot);
        }
        previousSlot = -1;
    }
}
