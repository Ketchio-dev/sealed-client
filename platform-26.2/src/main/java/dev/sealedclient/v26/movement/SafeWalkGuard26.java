package dev.sealedclient.v26.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/**
 * One-tick authorization bridge for the Safe Walk vanilla edge hook.
 *
 * <p>The movement service grants a lease only after arbitration. The hook
 * accepts that lease for the same local player on the prepared tick or the
 * immediately following player tick. It otherwise delegates to vanilla. A
 * lease holds no keys and cannot restore stale input.</p>
 */
public final class SafeWalkGuard26 {
    private static volatile Lease lease;

    private SafeWalkGuard26() {
    }

    static void authorize(
            Object owner,
            LocalPlayer player,
            Object level,
            int preparedTick
    ) {
        lease = new Lease(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(player, "player"),
                Objects.requireNonNull(level, "level"),
                preparedTick
        );
    }

    static void release(Object owner) {
        Lease current = lease;
        if (current != null && current.owner() == owner) {
            lease = null;
        }
    }

    /**
     * Called only from the local client movement hook.
     */
    public static boolean shouldStayOnGround(Player candidate) {
        Lease current = lease;
        if (current == null
                || candidate != current.player()
                || !(candidate instanceof LocalPlayer player)) {
            return false;
        }
        long age = (long) player.tickCount - current.preparedTick();
        if (age < 0 || age > 1) {
            lease = null;
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        boolean ready = client.player == player
                && client.level == current.level()
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected()
                && client.gui.screen() == null
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isSpectator()
                && !player.isPassenger()
                && !player.isInLiquid()
                && !player.isFallFlying()
                && player.onGround()
                && !player.input.keyPresses.jump()
                && !player.input.keyPresses.shift();
        if (!ready) {
            lease = null;
        }
        return ready;
    }

    static boolean leaseIsCurrent(
            int preparedTick,
            int playerTick,
            boolean contextMatches
    ) {
        long age = (long) playerTick - preparedTick;
        return contextMatches && age >= 0 && age <= 1;
    }

    private record Lease(
            Object owner,
            LocalPlayer player,
            Object level,
            int preparedTick
    ) {
    }
}
