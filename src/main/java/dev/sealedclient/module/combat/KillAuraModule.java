package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.EnumSetting;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.RotationApplier;
import dev.sealedclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class KillAuraModule extends Module implements TickableModule {
    private static final String OWNER = "kill_aura";
    private static final int PRIORITY = 60;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final RotationApplier rotations;
    private final EnumSetting<TargetType> targets = addSetting(new EnumSetting<>(
            "targets",
            "Targets",
            "Entity types that can be targeted.",
            TargetType.PLAYERS
    ));
    private final DoubleSetting range = addSetting(new DoubleSetting(
            "range",
            "Range",
            "Maximum attack distance.",
            3.5,
            2.0,
            6.0,
            0.1
    ));
    private final DoubleSetting wallRange = addSetting(new DoubleSetting(
            "wall_range",
            "Wall range",
            "Maximum distance when the target is not visible.",
            2.5,
            0.0,
            4.0,
            0.1
    ));
    private final DoubleSetting cooldown = addSetting(new DoubleSetting(
            "cooldown",
            "Cooldown",
            "Required vanilla attack-strength scale.",
            0.95,
            0.50,
            1.00,
            0.05
    ));
    private final BooleanSetting rotate = addSetting(new BooleanSetting(
            "rotate",
            "Rotate",
            "Face the target before attacking.",
            true
    ));
    private final BooleanSetting pauseUsing = addSetting(new BooleanSetting(
            "pause_using",
            "Pause while using",
            "Do not interrupt eating, blocking, or charging a bow.",
            true
    ));

    public KillAuraModule(FriendManager friends, ActionCoordinator actions, RotationApplier rotations) {
        super(
                "kill_aura",
                "Kill Aura",
                "Attacks the nearest valid target using vanilla attack cooldowns.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.friends = Objects.requireNonNull(friends, "friends");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.rotations = Objects.requireNonNull(rotations, "rotations");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!CombatUtil.isReady(minecraft)
                || (pauseUsing.get() && minecraft.player.isUsingItem())
                || minecraft.player.getAttackStrengthScale(0.0f) < cooldown.get()) {
            return;
        }

        double maxRange = range.get();
        List<Entity> nearby = minecraft.level.getEntities(
                minecraft.player,
                minecraft.player.getBoundingBox().inflate(maxRange),
                entity -> CombatUtil.isAttackableLiving(
                        minecraft.player,
                        entity,
                        friends,
                        targets.get().players,
                        targets.get().hostiles
                )
        );
        LivingEntity target = nearby.stream()
                .filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast)
                .filter(entity -> inRange(minecraft, entity))
                .min(Comparator.comparingDouble(minecraft.player::distanceToSqr))
                .orElse(null);
        if (target == null
                || !actions.claim(ActionCoordinator.Channel.ATTACK, OWNER, PRIORITY, 1)) {
            return;
        }
        if (rotate.get()
                && actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
            CombatUtil.rotateToward(minecraft, rotations, OWNER, PRIORITY, target.getEyePosition());
        }
        minecraft.gameMode.attack(minecraft.player, target);
        minecraft.player.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        actions.releaseOwner(minecraft, OWNER);
    }

    private boolean inRange(Minecraft minecraft, LivingEntity entity) {
        double distanceSquared = minecraft.player.distanceToSqr(entity);
        if (distanceSquared > range.get() * range.get()) {
            return false;
        }
        return minecraft.player.hasLineOfSight(entity)
                || distanceSquared <= wallRange.get() * wallRange.get();
    }

    private enum TargetType {
        PLAYERS(true, false),
        HOSTILES(false, true),
        PLAYERS_AND_HOSTILES(true, true);

        private final boolean players;
        private final boolean hostiles;

        TargetType(boolean players, boolean hostiles) {
            this.players = players;
            this.hostiles = hostiles;
        }
    }
}
