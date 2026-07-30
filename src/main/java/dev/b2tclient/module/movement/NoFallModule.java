package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A deliberately narrow no-fall implementation: it only deploys an already
 * equipped, usable elytra. It never spoofs ground state or emits tick packets.
 */
public final class NoFallModule extends Module implements TickableModule {
    private static final int PRIORITY = 85;

    private final ActionCoordinator actions;
    private final MovementSafetyController safety = new MovementSafetyController();
    private final DoubleSetting triggerDistance = addSetting(new DoubleSetting(
            "trigger_distance",
            "Trigger distance",
            "Accumulated fall distance before attempting one elytra deployment.",
            3.2,
            2.5,
            10.0,
            0.1
    ));
    private boolean attemptedThisFall;
    private Object activeContext;

    public NoFallModule(ActionCoordinator actions) {
        super(
                "no_fall",
                "No Fall",
                "Deploys an equipped elytra once during a dangerous fall.",
                Category.MOVEMENT,
                false,
                ModuleRisk.PACKET
        );
        this.actions = actions;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        MovementSafetyController.Observation observation =
                B2TMovementSupport.safetyObservation(minecraft);
        if (!java.util.Objects.equals(activeContext, observation.context())) {
            activeContext = observation.context();
            attemptedThisFall = false;
        }
        MovementSafetyController.Decision decision = safety.observe(observation);
        if (!B2TMovementSupport.canControl(minecraft) || !decision.canApply()) {
            actions.releaseOwner(minecraft, id());
            return;
        }
        if (minecraft.player.onGround() || minecraft.player.isInWater()) {
            attemptedThisFall = false;
            actions.releaseOwner(minecraft, id());
            return;
        }
        if (attemptedThisFall
                || minecraft.player.isFallFlying()
                || minecraft.player.fallDistance < triggerDistance.get()) {
            return;
        }

        ItemStack chest = minecraft.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)
                || (chest.isDamageableItem()
                && chest.getMaxDamage() - chest.getDamageValue() <= 1)
                || !actions.claim(ActionCoordinator.Channel.NETWORK, id(), PRIORITY, 2)) {
            return;
        }

        attemptedThisFall = true;
        minecraft.player.startFallFlying();
        minecraft.player.connection.send(new ServerboundPlayerCommandPacket(
                minecraft.player,
                ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
        ));
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        resetState(minecraft);
    }

    private void resetState(Minecraft minecraft) {
        attemptedThisFall = false;
        activeContext = null;
        safety.reset();
        actions.releaseOwner(minecraft, id());
    }
}
