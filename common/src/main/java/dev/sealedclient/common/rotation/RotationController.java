package dev.sealedclient.common.rotation;

import java.util.Objects;
import java.util.Optional;

/**
 * Collects per-tick rotation bids from modules and resolves them to at most one
 * winning angle, so competing modules can never fight over the player's aim.
 *
 * <p>Platform code calls {@link #beginTick()} at the start of a tick, modules
 * call {@link #request}, and the platform applies {@link #resolve} once at the
 * end of the tick. This class is deliberately free of Minecraft types so the
 * angle maths can be unit tested on both platforms.</p>
 *
 * <p>Turn rate is unlimited by default ({@value #UNLIMITED_DEGREES_PER_TICK}
 * degrees per tick), which reproduces the previous behaviour of writing the
 * target angle directly. Lowering it makes the client ease into the target over
 * several ticks instead.</p>
 */
public final class RotationController {
    /** A full half-turn per tick, i.e. effectively no smoothing. */
    public static final float UNLIMITED_DEGREES_PER_TICK = 180.0f;

    private RotationRequest winner;
    private float degreesPerTick = UNLIMITED_DEGREES_PER_TICK;

    /** Clears the previous tick's bids. Call once at the start of every tick. */
    public void beginTick() {
        winner = null;
    }

    /**
     * Sets the maximum angular change applied per tick.
     *
     * @param limit degrees per tick, clamped to (0, 180]
     */
    public void setDegreesPerTick(float limit) {
        if (!Float.isFinite(limit) || limit <= 0.0f) {
            throw new IllegalArgumentException("degreesPerTick must be positive and finite");
        }
        this.degreesPerTick = Math.min(limit, UNLIMITED_DEGREES_PER_TICK);
    }

    public float degreesPerTick() {
        return degreesPerTick;
    }

    /**
     * Bids to aim the player at the given angles this tick.
     *
     * <p>The highest priority wins. Equal priorities keep the earliest bid, so a
     * module that ran first in the tick order is not displaced by a later one of
     * the same rank.</p>
     *
     * @return {@code true} if this request is currently winning
     */
    public boolean request(String owner, int priority, float yaw, float pitch) {
        return request(new RotationRequest(owner, priority, yaw, pitch));
    }

    public boolean request(RotationRequest request) {
        Objects.requireNonNull(request, "request");
        boolean sameOwner = winner != null && winner.owner().equals(request.owner());
        if (winner == null || sameOwner || request.priority() > winner.priority()) {
            winner = request;
            return true;
        }
        return false;
    }

    /** The winning bid for this tick, if any module asked to aim. */
    public Optional<RotationRequest> resolve() {
        return Optional.ofNullable(winner);
    }

    /** Drops any pending bid, e.g. on disconnect or panic. */
    public void clear() {
        winner = null;
    }

    /**
     * Steps {@code currentYaw} toward {@code targetYaw} by at most the configured
     * turn rate, taking the shorter way around the -180/180 seam.
     */
    public float stepYaw(float currentYaw, float targetYaw) {
        float delta = wrapDegrees(targetYaw - currentYaw);
        return currentYaw + clampToTurnRate(delta);
    }

    /**
     * Steps {@code currentPitch} toward {@code targetPitch} by at most the
     * configured turn rate. Pitch does not wrap; it is clamped to [-90, 90].
     */
    public float stepPitch(float currentPitch, float targetPitch) {
        float clampedTarget = Math.max(-90.0f, Math.min(90.0f, targetPitch));
        float delta = clampedTarget - currentPitch;
        return Math.max(-90.0f, Math.min(90.0f, currentPitch + clampToTurnRate(delta)));
    }

    private float clampToTurnRate(float delta) {
        return Math.max(-degreesPerTick, Math.min(degreesPerTick, delta));
    }

    /** Normalises an angle to (-180, 180]. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped > 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped <= -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
