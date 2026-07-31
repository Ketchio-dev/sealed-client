package dev.sealedclient.v26;

import dev.sealedclient.common.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The single place where the 26.2 adapter writes the player's aim.
 *
 * <p>Mirrors {@code dev.sealedclient.service.RotationApplier} on 1.21.4 so both
 * platforms arbitrate aim the same way: modules bid through {@link #request} and
 * only the highest-priority bid on a tick is written. Before this existed, the
 * 26.2 automations each called {@code setYRot}/{@code setXRot} directly and
 * silently overwrote one another within a single tick.</p>
 *
 * <p>The winning angle is applied immediately rather than deferred, because the
 * 26.2 automations serialise the current aim into use and rotation packets in
 * the same tick they aim.</p>
 */
public final class RotationApplier26 {
    private final RotationController controller = new RotationController();

    private boolean intervening;
    private float restoreYaw;
    private float restorePitch;
    private float lastWrittenYaw;
    private float lastWrittenPitch;

    public void beginTick() {
        controller.beginTick();
    }

    /**
     * Bids to aim the player, writing the angle at once if the bid wins.
     *
     * @return {@code true} if this owner currently holds the aim this tick
     */
    public boolean request(Minecraft client, String owner, int priority, float yaw, float pitch) {
        if (client == null || client.player == null) {
            return false;
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return false;
        }
        if (!controller.request(owner, priority, yaw, pitch)) {
            return false;
        }

        LocalPlayer player = client.player;
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

    /** The angle actually written this tick, for callers that must echo it in a packet. */
    public float appliedYaw() {
        return lastWrittenYaw;
    }

    public float appliedPitch() {
        return lastWrittenPitch;
    }

    /** Restores the pre-intervention aim once no automation wants to aim any more. */
    public void endTick(Minecraft client) {
        if (!intervening || controller.resolve().isPresent()) {
            return;
        }
        intervening = false;
        if (client == null || client.player == null) {
            return;
        }
        LocalPlayer player = client.player;
        if (player.getYRot() != lastWrittenYaw || player.getXRot() != lastWrittenPitch) {
            // The user moved the mouse, or the server corrected us. Leave it alone.
            return;
        }
        player.setYRot(restoreYaw);
        player.setXRot(restorePitch);
    }

    public void setDegreesPerTick(float limit) {
        controller.setDegreesPerTick(limit);
    }

    public float degreesPerTick() {
        return controller.degreesPerTick();
    }

    public boolean intervening() {
        return intervening;
    }

    /** Drops all state without restoring, e.g. on disconnect or panic. */
    public void reset() {
        controller.clear();
        intervening = false;
    }
}
