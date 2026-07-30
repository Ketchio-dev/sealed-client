package dev.b2tclient.module.hud;

import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import dev.b2tclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Locale;
import java.util.Objects;

public final class TargetHudModule extends HudModule implements TickableModule {
    private final IntegerSetting range = addSetting(new IntegerSetting(
            "range",
            "Range",
            "Maximum distance used when selecting the nearest player.",
            64,
            8,
            256,
            8
    ));

    private final FriendManager friendManager;
    private String primaryLine;
    private String detailLine;
    private boolean selectedFriend;

    public TargetHudModule(FriendManager friendManager) {
        super(
                "target_hud",
                "Target HUD",
                "Shows crosshair or nearest-player details and identifies friends.",
                false
        );
        this.friendManager = Objects.requireNonNull(friendManager, "friendManager");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null || minecraft.level == null) {
            clear();
            return;
        }

        AbstractClientPlayer target = crosshairPlayer(minecraft, localPlayer);
        if (target == null) {
            target = nearestPlayer(minecraft, localPlayer);
        }
        if (target == null) {
            clear();
            return;
        }

        selectedFriend = friendManager.isFriend(target);
        String friendLabel = selectedFriend ? " [Friend]" : "";
        primaryLine = "Target: " + target.getGameProfile().getName() + friendLabel;

        int ping = targetPing(minecraft, target);
        detailLine = String.format(
                Locale.ROOT,
                "HP %.1f + %.1f  Armor %d  %.1fm  Ping %s",
                target.getHealth(),
                target.getAbsorptionAmount(),
                target.getArmorValue(),
                localPlayer.distanceTo(target),
                ping < 0 ? "?" : ping + " ms"
        );
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (primaryLine == null) {
            return 0;
        }
        context.text(
                primaryLine,
                x,
                y,
                selectedFriend ? HudRenderContext.ACCENT : HudRenderContext.TEXT
        );
        context.text(detailLine, x, y + 10, HudRenderContext.MUTED);
        return 20;
    }

    private static AbstractClientPlayer crosshairPlayer(
            Minecraft minecraft,
            LocalPlayer localPlayer
    ) {
        Entity entity = minecraft.hitResult instanceof EntityHitResult entityHit
                ? entityHit.getEntity()
                : minecraft.crosshairPickEntity;
        if (entity instanceof AbstractClientPlayer player && valid(player, localPlayer)) {
            return player;
        }
        return null;
    }

    private AbstractClientPlayer nearestPlayer(Minecraft minecraft, LocalPlayer localPlayer) {
        double maximumDistanceSquared = range.get() * (double) range.get();
        AbstractClientPlayer nearest = null;
        double nearestDistanceSquared = maximumDistanceSquared;
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (!valid(player, localPlayer)) {
                continue;
            }
            double distanceSquared = localPlayer.distanceToSqr(player);
            if (distanceSquared <= nearestDistanceSquared) {
                nearest = player;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    private static boolean valid(AbstractClientPlayer player, LocalPlayer localPlayer) {
        return player != localPlayer && player.isAlive() && !player.isSpectator();
    }

    private static int targetPing(Minecraft minecraft, AbstractClientPlayer player) {
        if (minecraft.getConnection() == null) {
            return -1;
        }
        PlayerInfo info = minecraft.getConnection().getPlayerInfo(player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    private void clear() {
        primaryLine = null;
        detailLine = null;
        selectedFriend = false;
    }
}
