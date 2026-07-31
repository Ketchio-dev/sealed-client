package dev.sealedclient.service;

import dev.sealedclient.common.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The single place where Sealed Client writes the player's aim.
 *
 * <p>Modules never touch {@code setYRot}/{@code setXRot} themselves; they bid
 * through {@link #request} and the highest-priority bid on a tick wins. That
 * stops two combat modules from overwriting each other's aim in the same tick.</p>
 *
 * <p>The winning angle is written <em>immediately</em> rather than at the end of
 * the tick, because {@code MultiPlayerGameMode.useItem} serialises the player's
 * current yaw and pitch straight into {@code ServerboundUseItemPacket}. Modules
 * such as Auto Mend and Quiver aim and then use an item within the same tick, so
 * a deferred write would send the wrong angle. A later, higher-priority bid
 * simply overwrites the earlier one, which leaves the same final state a
 * deferred pass would have produced.</p>
 *
 * <p>When a tick passes with no bid at all, the aim is restored to whatever it
 * was before the client first intervened — unless something else has moved it
 * since, in which case the restore is abandoned so we never fight the user or a
 * server correction.</p>
 */
public final class RotationApplier {
    private final RotationController controller = new RotationController();

    private boolean intervening;
    private float restoreYaw;
    private float restorePitch;
    private float lastWrittenYaw;
    private float lastWrittenPitch;

    /** Clears the previous tick's bids. Call once at the start of every client tick. */
    public void beginTick() {
        controller.beginTick();
    }

    /**
     * Bids to aim the player, applying the angle at once if the bid wins.
     *
     * @return {@code true} if this owner currently holds the aim this tick
     */
    public boolean request(Minecraft minecraft, String owner, int priority, float yaw, float pitch) {
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return false;
        }
        if (!controller.request(owner, priority, yaw, pitch)) {
            return false;
        }

        LocalPlayer player = minecraft.player;
        if (!intervening) {
            restoreYaw = player.getYRot();
            restorePitch = player.getXRot();
            intervening = true;
        }

        float nextYaw = controller.stepYaw(player.getYRot(), yaw);
        float nextPitch = controller.stepPitch(player.getXRot(), pitch);
        player.setYRot(nextYaw);
        player.setXRot(nextPitch);
        lastWrittenYaw = nextYaw;
        lastWrittenPitch = nextPitch;
        return true;
    }

    /**
     * Restores the pre-intervention aim once no module wants to aim any more.
     * Call once at the end of every client tick, after modules have ticked.
     */
    public void endTick(Minecraft minecraft) {
        if (!intervening || controller.resolve().isPresent()) {
            return;
        }
        intervening = false;
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        boolean untouched = player.getYRot() == lastWrittenYaw && player.getXRot() == lastWrittenPitch;
        if (!untouched) {
            // The user moved the mouse, or the server corrected us. Leave it alone.
            return;
        }
        player.setYRot(restoreYaw);
        player.setXRot(restorePitch);
    }

    /** Maximum degrees the aim may move per tick. */
    public void setDegreesPerTick(float limit) {
        controller.setDegreesPerTick(limit);
    }

    public float degreesPerTick() {
        return controller.degreesPerTick();
    }

    /** Whether a module currently owns the aim. */
    public boolean intervening() {
        return intervening;
    }

    /** Drops all state without restoring, e.g. on disconnect or panic. */
    public void reset() {
        controller.clear();
        intervening = false;
    }
}
