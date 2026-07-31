package dev.sealedclient.platform;

import net.minecraft.client.player.LocalPlayer;

/**
 * The one place that knows how movement input is spelled.
 *
 * <p>Like the hotbar slot, these fields were renamed between target versions:
 * {@code forwardImpulse} and {@code leftImpulse} became {@code zza} and
 * {@code xxa}. Keeping the access here means the rename is one file rather
 * than every movement module.</p>
 */
public final class MovementInputAccess {
    private MovementInputAccess() {
    }

    /** Forward input, positive when walking forwards. */
    public static float forward(LocalPlayer player) {
        return player.input.forwardImpulse;
    }

    /** Sideways input, positive when strafing left. */
    public static float left(LocalPlayer player) {
        return player.input.leftImpulse;
    }

    /** Whether the player is asking to move at all. */
    public static boolean isMoving(LocalPlayer player) {
        return forward(player) != 0.0f || left(player) != 0.0f;
    }

    /** Cancels movement input for this tick. */
    public static void clear(LocalPlayer player) {
        player.input.forwardImpulse = 0.0f;
        player.input.leftImpulse = 0.0f;
    }
}
