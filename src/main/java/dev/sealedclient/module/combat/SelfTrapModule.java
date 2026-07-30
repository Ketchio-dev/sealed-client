package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SelfTrapModule extends Module implements TickableModule {
    private static final String OWNER = "self_trap";
    private static final int PRIORITY = 72;

    private final ActionCoordinator actions;
    private final BooleanSetting sides = addSetting(new BooleanSetting(
            "sides",
            "Head sides",
            "Place additional blocks beside head height before closing the roof.",
            false
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Delay",
            "Ticks between successful placements.",
            2,
            0,
            20,
            1
    ));
    private int cooldown;

    public SelfTrapModule(ActionCoordinator actions) {
        super(
                "self_trap",
                "Self Trap",
                "Builds a conservative obsidian roof around the player's current position.",
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
        if (!CombatUtil.isReady(minecraft) || cooldown > 0 || !minecraft.player.onGround()) {
            return;
        }
        int slot = CombatUtil.findHotbarItem(minecraft.player, Items.OBSIDIAN);
        BlockPos placement = nextPlacement(minecraft);
        if (slot < 0
                || placement == null
                || !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1)
                || !actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)) {
            return;
        }
        int previous = minecraft.player.getInventory().selected;
        minecraft.player.getInventory().setSelectedHotbarSlot(slot);
        boolean placed = CombatUtil.placeBlock(minecraft, placement, InteractionHand.MAIN_HAND);
        if (previous != slot) {
            minecraft.player.getInventory().setSelectedHotbarSlot(previous);
        }
        if (placed) {
            cooldown = delay.get();
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        cooldown = 0;
        actions.releaseOwner(minecraft, OWNER);
    }

    private BlockPos nextPlacement(Minecraft minecraft) {
        BlockPos feet = minecraft.player.blockPosition();
        List<BlockPos> positions = new ArrayList<>();
        if (sides.get()) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                positions.add(feet.above().relative(direction));
            }
        }
        positions.add(feet.above(2));
        return positions.stream()
                .filter(position -> minecraft.player.getEyePosition()
                        .distanceToSqr(position.getCenter()) <= 25.0)
                .filter(position -> CombatUtil.canPlaceBlock(minecraft, position))
                .findFirst()
                .orElse(null);
    }
}
