package dev.sealedclient.module.combat;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.EnumSetting;
import dev.sealedclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

public final class TriggerBotModule extends Module implements TickableModule {
    private final FriendManager friendManager;
    private final EnumSetting<Targets> targets = addSetting(new EnumSetting<>(
            "targets",
            "Targets",
            "Entity types that may be attacked.",
            Targets.PLAYERS_AND_HOSTILES
    ));

    private final DoubleSetting cooldown = addSetting(new DoubleSetting(
            "cooldown",
            "Cooldown",
            "Required vanilla attack strength before attacking.",
            0.95,
            0.50,
            1.00,
            0.05
    ));

    public TriggerBotModule() {
        this(new FriendManager());
    }

    public TriggerBotModule(FriendManager friendManager) {
        super(
                "trigger_bot",
                "Trigger Bot",
                "Attacks a valid entity when the crosshair is directly over it.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.friendManager = friendManager;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null
                || minecraft.gameMode == null
                || minecraft.screen != null
                || minecraft.player.isUsingItem()
                || minecraft.player.getAttackStrengthScale(0.0f) < cooldown.get()
                || !(minecraft.hitResult instanceof EntityHitResult hitResult)) {
            return;
        }

        Entity entity = hitResult.getEntity();
        if (!(entity instanceof LivingEntity living)
                || !living.isAlive()
                || entity == minecraft.player
                || entity instanceof Player player && friendManager.isFriend(player)
                || !minecraft.player.canAttack(living)
                || !targets.get().allows(entity)) {
            return;
        }

        minecraft.gameMode.attack(minecraft.player, entity);
        minecraft.player.swing(InteractionHand.MAIN_HAND);
    }

    private enum Targets {
        PLAYERS {
            @Override
            boolean allows(Entity entity) {
                return entity instanceof Player;
            }
        },
        HOSTILES {
            @Override
            boolean allows(Entity entity) {
                return entity instanceof Enemy;
            }
        },
        PLAYERS_AND_HOSTILES {
            @Override
            boolean allows(Entity entity) {
                return entity instanceof Player || entity instanceof Enemy;
            }
        },
        ALL_LIVING {
            @Override
            boolean allows(Entity entity) {
                return entity instanceof LivingEntity;
            }
        };

        abstract boolean allows(Entity entity);
    }
}
