package dev.sealedclient.v26.movement;

import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure policy for preserving local camera rotation while still accepting
 * server position and velocity corrections.
 */
public final class NoRotatePolicy26 {
    public static boolean shouldReplacePositionArguments(
            PositionDecision decision
    ) {
        return decision != null && decision.modified();
    }

    public static boolean shouldApplyServerRotation(boolean preserve) {
        return !preserve;
    }

    public Decision decide(
            Observation observation,
            Configuration configuration,
            Rotation current,
            Rotation incoming,
            boolean relativeYaw,
            boolean relativePitch
    ) {
        if (observation == null
                || configuration == null
                || current == null
                || incoming == null
                || !finite(current)
                || !finite(incoming)) {
            return Decision.unchanged(incoming);
        }
        boolean active = observation.enabled()
                && observation.sessionActive()
                && observation.playerPresent()
                && observation.playerAlive();
        if (!active) {
            return Decision.unchanged(incoming);
        }

        boolean preserveYaw = configuration.preserveYaw();
        boolean preservePitch = configuration.preservePitch();
        float packetYaw = preserveYaw
                ? preservedPacketComponent(current.yaw(), relativeYaw)
                : incoming.yaw();
        float packetPitch = preservePitch
                ? preservedPacketComponent(current.pitch(), relativePitch)
                : incoming.pitch();
        return new Decision(
                new Rotation(packetYaw, packetPitch),
                preserveYaw,
                preservePitch,
                preserveYaw,
                preservePitch
        );
    }

    /**
     * Resolves velocity exactly as vanilla first, including
     * {@link Relative#ROTATE_DELTA}, while retaining raw position components
     * and their relative flags. This preserves vanilla's old-position
     * interpolation history. Configured camera axes become absolute current
     * values; unconfigured axes retain their raw values and flags.
     */
    public PositionDecision decidePositionCorrection(
            Observation observation,
            Configuration configuration,
            PositionMoveRotation current,
            PositionMoveRotation correction,
            Set<Relative> relatives
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(correction, "correction");
        Objects.requireNonNull(relatives, "relatives");
        Set<Relative> originalRelatives = Set.copyOf(relatives);
        if (!active(observation)
                || (!configuration.preserveYaw()
                && !configuration.preservePitch())) {
            return new PositionDecision(
                    correction,
                    originalRelatives,
                    false
            );
        }
        PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(
                current,
                correction,
                originalRelatives
        );
        EnumSet<Relative> adjustedRelatives =
                originalRelatives.isEmpty()
                        ? EnumSet.noneOf(Relative.class)
                        : EnumSet.copyOf(originalRelatives);
        adjustedRelatives.remove(Relative.DELTA_X);
        adjustedRelatives.remove(Relative.DELTA_Y);
        adjustedRelatives.remove(Relative.DELTA_Z);
        adjustedRelatives.remove(Relative.ROTATE_DELTA);
        if (configuration.preserveYaw()) {
            adjustedRelatives.remove(Relative.Y_ROT);
        }
        if (configuration.preservePitch()) {
            adjustedRelatives.remove(Relative.X_ROT);
        }
        PositionMoveRotation preserved = new PositionMoveRotation(
                correction.position(),
                absolute.deltaMovement(),
                configuration.preserveYaw()
                        ? current.yRot()
                        : correction.yRot(),
                configuration.preservePitch()
                        ? current.xRot()
                        : correction.xRot()
        );
        return new PositionDecision(
                preserved,
                Set.copyOf(adjustedRelatives),
                true
        );
    }

    /**
     * A relative correction is neutralized with zero; an absolute correction
     * is replaced with the current camera component.
     */
    public static float preservedPacketComponent(float current, boolean relative) {
        return relative ? 0.0F : current;
    }

    private static boolean finite(Rotation rotation) {
        return Float.isFinite(rotation.yaw()) && Float.isFinite(rotation.pitch());
    }

    private static boolean active(Observation observation) {
        return observation != null
                && observation.enabled()
                && observation.sessionActive()
                && observation.playerPresent()
                && observation.playerAlive();
    }

    public record Configuration(boolean preserveYaw, boolean preservePitch) {
        public static final Configuration DEFAULT = new Configuration(true, true);
    }

    public record Observation(
            boolean enabled,
            boolean sessionActive,
            boolean playerPresent,
            boolean playerAlive
    ) {
    }

    public record Rotation(float yaw, float pitch) {
    }

    public record Decision(
            Rotation packetRotation,
            boolean preserveYaw,
            boolean preservePitch,
            boolean removeRelativeYaw,
            boolean removeRelativePitch
    ) {
        private static Decision unchanged(Rotation incoming) {
            Rotation safe = incoming == null ? new Rotation(0.0F, 0.0F) : incoming;
            return new Decision(safe, false, false, false, false);
        }
    }

    public record PositionDecision(
            PositionMoveRotation correction,
            Set<Relative> relatives,
            boolean modified
    ) {
        public PositionDecision {
            correction = Objects.requireNonNull(correction, "correction");
            relatives = Set.copyOf(
                    Objects.requireNonNull(relatives, "relatives")
            );
        }
    }
}
