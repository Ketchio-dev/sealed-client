package dev.sealedclient.common.rotation;

import java.util.Objects;

/**
 * A single module's bid to aim the player somewhere on the current tick.
 *
 * <p>Requests are collected by {@link RotationController} and only the winning
 * one is ever written to the player, so two modules that both want to aim on the
 * same tick can no longer overwrite each other.</p>
 *
 * @param owner    stable module identifier, used for diagnostics and tie-breaks
 * @param priority higher wins; ties are resolved first-come
 * @param yaw      requested yaw in degrees
 * @param pitch    requested pitch in degrees, clamped to [-90, 90] on apply
 */
public record RotationRequest(String owner, int priority, float yaw, float pitch) {
    public RotationRequest {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("yaw and pitch must be finite");
        }
    }
}
