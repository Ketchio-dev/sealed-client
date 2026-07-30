package dev.sealedclient.module.combat;

import dev.sealedclient.combat.CombatUtil;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class BowAimModule extends Module implements TickableModule {
    private static final String OWNER = "bow_aim";
    private static final int PRIORITY = 58;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final DoubleSetting range = addSetting(new DoubleSetting(
            "range",
            "Range",
            "Maximum enemy distance.",
            48.0,
            8.0,
            96.0,
            1.0
    ));
    private final DoubleSetting projectileSpeed = addSetting(new DoubleSetting(
            "projectile_speed",
            "Projectile speed",
            "Estimated fully-drawn projectile speed used for target leading.",
            3.0,
            1.0,
            4.0,
            0.1
    ));
    private final DoubleSetting gravity = addSetting(new DoubleSetting(
            "gravity",
            "Gravity",
            "Estimated projectile gravity per tick.",
            0.05,
            0.0,
            0.15,
            0.01
    ));

    public BowAimModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "bow_aim",
                "Bow Aim",
                "Leads the nearest non-friend target while a bow or crossbow is being used.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.friends = Objects.requireNonNull(friends, "friends");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!CombatUtil.isReady(minecraft) || !minecraft.player.isUsingItem()) {
            return;
        }
        ItemStack used = minecraft.player.getUseItem();
        if (!(used.getItem() instanceof BowItem)
                && !(used.getItem() instanceof CrossbowItem)) {
            return;
        }
        Player target = CombatUtil.nearestEnemyPlayer(
                minecraft,
                friends,
                range.get()
        ).orElse(null);
        if (target == null
                || !actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
            return;
        }

        double distance = minecraft.player.getEyePosition().distanceTo(target.getEyePosition());
        double flightTicks = Math.min(40.0, distance / projectileSpeed.get());
        Vec3 predicted = target.getEyePosition()
                .add(target.getDeltaMovement().scale(flightTicks))
                .add(0.0, 0.5 * gravity.get() * flightTicks * flightTicks, 0.0);
        CombatUtil.rotateToward(minecraft.player, predicted);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        actions.releaseOwner(minecraft, OWNER);
    }
}
