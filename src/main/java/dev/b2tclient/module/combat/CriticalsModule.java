package dev.b2tclient.module.combat;

import dev.b2tclient.combat.CombatUtil;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Objects;

public final class CriticalsModule extends Module implements TickableModule {
    private static final String OWNER = "criticals";
    private static final int PRIORITY = 75;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final BooleanSetting jumpAssist = addSetting(new BooleanSetting(
            "jump_assist",
            "Jump assist",
            "Start a vanilla jump when a fully charged grounded attack is requested.",
            true
    ));
    private final DoubleSetting cooldown = addSetting(new DoubleSetting(
            "cooldown",
            "Cooldown",
            "Required attack-strength scale for the assisted hit.",
            0.95,
            0.50,
            1.00,
            0.05
    ));

    private LivingEntity queuedTarget;
    private int queueTicks;

    public CriticalsModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "criticals",
                "Criticals",
                "Assists vanilla jump criticals against the entity under the crosshair.",
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
            clear();
            return;
        }
        if (queueTicks > 0) {
            queueTicks--;
            if (canCritical(minecraft, queuedTarget)
                    && actions.claim(ActionCoordinator.Channel.ATTACK, OWNER, PRIORITY, 1)) {
                minecraft.gameMode.attack(minecraft.player, queuedTarget);
                minecraft.player.swing(InteractionHand.MAIN_HAND);
                clear();
                return;
            }
            if (queueTicks == 0 || queuedTarget == null || !queuedTarget.isAlive()) {
                clear();
            }
        }

        if (!jumpAssist.get()
                || !minecraft.options.keyAttack.isDown()
                || !minecraft.player.onGround()
                || minecraft.player.isInWater()
                || minecraft.player.isPassenger()
                || minecraft.player.getAttackStrengthScale(0.0f) < cooldown.get()
                || !(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target)
                || !CombatUtil.isAttackableLiving(
                        minecraft.player,
                        target,
                        friends,
                        true,
                        true
                )
                || minecraft.player.distanceToSqr(target) > 16.0
                || !actions.claim(ActionCoordinator.Channel.MOVEMENT, OWNER, PRIORITY, 2)) {
            return;
        }
        queuedTarget = target;
        queueTicks = 8;
        minecraft.player.jumpFromGround();
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        clear();
        actions.releaseOwner(minecraft, OWNER);
    }

    private boolean canCritical(Minecraft minecraft, LivingEntity target) {
        return target != null
                && target.isAlive()
                && minecraft.player.fallDistance > 0.0f
                && !minecraft.player.onGround()
                && !minecraft.player.isInWater()
                && !minecraft.player.isPassenger()
                && minecraft.player.distanceToSqr(target) <= 16.0
                && minecraft.player.getAttackStrengthScale(0.0f) >= cooldown.get();
    }

    private void clear() {
        queuedTarget = null;
        queueTicks = 0;
    }
}
