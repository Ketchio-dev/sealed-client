package dev.sealedclient.v26.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, deterministic target and low-arc ballistic solver for Bow Aim.
 *
 * <p>The solver deliberately models only constant projectile speed and
 * gravity. Minecraft drag, target acceleration, server interpolation and
 * latency are not guessed. Runtime callers therefore supply bounded target
 * velocity and keep lead time short.</p>
 */
public final class BowAimDecisionEngine26 {
    public static final int MAXIMUM_CANDIDATES = 64;
    private static final double EPSILON = 1.0E-7;
    private static final double MAXIMUM_TARGET_SPEED = 4.0;

    private BowAimDecisionEngine26() {
    }

    public static Optional<Solution> select(
            Vector3 origin,
            double currentYaw,
            double currentPitch,
            double projectileSpeed,
            List<Candidate> candidates,
            Limits limits
    ) {
        if (origin == null
                || !origin.finite()
                || !finite(currentYaw)
                || !finite(currentPitch)
                || !finite(projectileSpeed)
                || projectileSpeed <= EPSILON
                || candidates == null
                || limits == null) {
            return Optional.empty();
        }

        double maximumDistanceSquared =
                limits.maximumRange() * limits.maximumRange();
        return candidates.stream()
                .limit(MAXIMUM_CANDIDATES)
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.safe(maximumDistanceSquared))
                .sorted(Comparator
                        .comparingDouble(Candidate::distanceSquared)
                        .thenComparingInt(Candidate::entityId))
                .map(candidate -> solve(
                        origin,
                        currentYaw,
                        currentPitch,
                        projectileSpeed,
                        candidate,
                        limits
                ))
                .flatMap(Optional::stream)
                .findFirst();
    }

    static Optional<Solution> solve(
            Vector3 origin,
            double currentYaw,
            double currentPitch,
            double projectileSpeed,
            Candidate candidate,
            Limits limits
    ) {
        Vector3 predicted = candidate.eyePosition();
        double flightTicks = Math.min(
                limits.maximumLeadTicks(),
                Math.sqrt(candidate.distanceSquared()) / projectileSpeed
        );
        BallisticRotation ballistic = null;
        for (int iteration = 0; iteration < 3; iteration++) {
            predicted = candidate.eyePosition().add(
                    candidate.velocity().scale(flightTicks)
            );
            ballistic = ballisticRotation(
                    origin,
                    predicted,
                    projectileSpeed,
                    limits.gravity()
            ).orElse(null);
            if (ballistic == null) {
                return Optional.empty();
            }
            flightTicks = Math.min(
                    limits.maximumLeadTicks(),
                    ballistic.flightTicks()
            );
        }

        double yawError = wrapDegrees(ballistic.yaw() - currentYaw);
        double pitchError = ballistic.pitch() - currentPitch;
        double angularError = Math.hypot(yawError, pitchError);
        if (!finite(angularError) || angularError > limits.fovDegrees()) {
            return Optional.empty();
        }

        double scale = angularError <= limits.maximumRotationDegreesPerTick()
                || angularError <= EPSILON
                ? 1.0
                : limits.maximumRotationDegreesPerTick() / angularError;
        double appliedYaw = wrapDegrees(currentYaw + yawError * scale);
        double appliedPitch = clamp(
                currentPitch + pitchError * scale,
                -90.0,
                90.0
        );
        return Optional.of(new Solution(
                candidate.entityId(),
                predicted,
                ballistic.yaw(),
                ballistic.pitch(),
                appliedYaw,
                appliedPitch,
                flightTicks,
                angularError
        ));
    }

    static Optional<BallisticRotation> ballisticRotation(
            Vector3 origin,
            Vector3 target,
            double speed,
            double gravity
    ) {
        if (origin == null
                || target == null
                || !origin.finite()
                || !target.finite()
                || !finite(speed)
                || speed <= EPSILON
                || !finite(gravity)
                || gravity < 0.0) {
            return Optional.empty();
        }
        double dx = target.x() - origin.x();
        double dy = target.y() - origin.y();
        double dz = target.z() - origin.z();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal <= EPSILON) {
            return Optional.empty();
        }

        double angle;
        if (gravity <= EPSILON) {
            angle = Math.atan2(dy, horizontal);
        } else {
            double speedSquared = speed * speed;
            double discriminant = speedSquared * speedSquared
                    - gravity * (
                            gravity * horizontal * horizontal
                                    + 2.0 * dy * speedSquared
                    );
            if (!finite(discriminant) || discriminant < 0.0) {
                return Optional.empty();
            }
            angle = Math.atan(
                    (speedSquared - Math.sqrt(discriminant))
                            / (gravity * horizontal)
            );
        }
        double horizontalVelocity = speed * Math.cos(angle);
        if (!finite(horizontalVelocity)
                || horizontalVelocity <= EPSILON) {
            return Optional.empty();
        }
        double flightTicks = horizontal / horizontalVelocity;
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(angle);
        if (!finite(yaw)
                || !finite(pitch)
                || !finite(flightTicks)
                || flightTicks < 0.0) {
            return Optional.empty();
        }
        return Optional.of(new BallisticRotation(
                wrapDegrees(yaw),
                clamp(pitch, -90.0, 90.0),
                flightTicks
        ));
    }

    static double wrapDegrees(double degrees) {
        if (!finite(degrees)) {
            return Double.NaN;
        }
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    public record Vector3(double x, double y, double z) {
        public boolean finite() {
            return BowAimDecisionEngine26.finite(x)
                    && BowAimDecisionEngine26.finite(y)
                    && BowAimDecisionEngine26.finite(z);
        }

        public Vector3 add(Vector3 other) {
            Objects.requireNonNull(other, "other");
            return new Vector3(
                    x + other.x,
                    y + other.y,
                    z + other.z
            );
        }

        public Vector3 scale(double factor) {
            return new Vector3(x * factor, y * factor, z * factor);
        }

        public double lengthSquared() {
            return x * x + y * y + z * z;
        }
    }

    public record Candidate(
            int entityId,
            Vector3 eyePosition,
            Vector3 velocity,
            double distanceSquared,
            boolean friend,
            boolean lineOfSight,
            boolean alive,
            boolean spectator
    ) {
        boolean safe(double maximumDistanceSquared) {
            return entityId >= 0
                    && eyePosition != null
                    && eyePosition.finite()
                    && velocity != null
                    && velocity.finite()
                    && velocity.lengthSquared()
                    <= MAXIMUM_TARGET_SPEED * MAXIMUM_TARGET_SPEED
                    && finite(distanceSquared)
                    && distanceSquared >= 0.0
                    && distanceSquared <= maximumDistanceSquared
                    && !friend
                    && lineOfSight
                    && alive
                    && !spectator;
        }
    }

    public record Limits(
            double maximumRange,
            double gravity,
            double maximumLeadTicks,
            double fovDegrees,
            double maximumRotationDegreesPerTick
    ) {
        public Limits {
            requireFinitePositive(maximumRange, "maximumRange");
            requireFiniteNonNegative(gravity, "gravity");
            requireFinitePositive(maximumLeadTicks, "maximumLeadTicks");
            if (maximumLeadTicks > 80.0) {
                throw new IllegalArgumentException(
                        "maximumLeadTicks cannot exceed 80"
                );
            }
            requireFinitePositive(fovDegrees, "fovDegrees");
            if (fovDegrees > 180.0) {
                throw new IllegalArgumentException(
                        "fovDegrees cannot exceed 180"
                );
            }
            requireFinitePositive(
                    maximumRotationDegreesPerTick,
                    "maximumRotationDegreesPerTick"
            );
            if (maximumRotationDegreesPerTick > 180.0) {
                throw new IllegalArgumentException(
                        "maximumRotationDegreesPerTick cannot exceed 180"
                );
            }
        }
    }

    public record Solution(
            int targetEntityId,
            Vector3 predictedPosition,
            double targetYaw,
            double targetPitch,
            double appliedYaw,
            double appliedPitch,
            double flightTicks,
            double angularError
    ) {
    }

    record BallisticRotation(
            double yaw,
            double pitch,
            double flightTicks
    ) {
    }

    private static void requireFinitePositive(double value, String name) {
        if (!finite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!finite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative"
            );
        }
    }
}
