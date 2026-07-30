package dev.b2tclient.v26.visual;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, deterministic projectile trajectory simulator for the 26.2 overlay.
 *
 * <p>This class deliberately has no Minecraft dependencies. The runtime maps
 * the held item to a {@link ProjectileType}, supplies the camera rotation, and
 * adapts the world ray cast to {@link CollisionQuery}. Both the number of ray
 * casts and the simulated path length have hard upper bounds.</p>
 *
 * <p>Physics are applied once after every collision-free full tick:
 * velocity is first multiplied by drag, gravity is subtracted from Y, and
 * optional forward acceleration is added. The standard player-thrown wind
 * charge profile has zero acceleration in 26.2, while the field remains
 * available for callers previewing a deflected hurting projectile.</p>
 */
public final class TrajectoryDecisionEngine26 {
    public static final int HARD_MAXIMUM_STEPS = 512;
    public static final double HARD_MAXIMUM_RANGE = 512.0;
    public static final double MINIMUM_BOW_DRAW = 0.1;

    private static final double EPSILON = 1.0E-9;
    private static final Collision MISS = new Collision(
            CollisionKind.NONE,
            1.0
    );

    private TrajectoryDecisionEngine26() {
    }

    /**
     * Returns the full-charge/default profile for a supported projectile.
     */
    public static ProjectileParameters parameters(ProjectileType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case BOW_ARROW -> bowParameters(1.0);
            case CROSSBOW_ARROW -> new ProjectileParameters(
                    type,
                    3.15,
                    0.99,
                    0.05,
                    0.0,
                    0.0
            );
            case CROSSBOW_FIREWORK -> new ProjectileParameters(
                    type,
                    1.6,
                    1.0,
                    0.0,
                    0.0,
                    0.0
            );
            case TRIDENT -> new ProjectileParameters(
                    type,
                    2.5,
                    0.99,
                    0.05,
                    0.0,
                    0.0
            );
            case SNOWBALL, EGG, ENDER_PEARL -> new ProjectileParameters(
                    type,
                    1.5,
                    0.99,
                    0.03,
                    0.0,
                    0.0
            );
            case SPLASH_POTION, LINGERING_POTION ->
                    new ProjectileParameters(
                            type,
                            0.5,
                            0.99,
                            0.05,
                            -20.0,
                            0.0
                    );
            case EXPERIENCE_BOTTLE -> new ProjectileParameters(
                    type,
                    0.7,
                    0.99,
                    0.07,
                    -20.0,
                    0.0
            );
            case WIND_CHARGE -> new ProjectileParameters(
                    type,
                    1.5,
                    1.0,
                    0.0,
                    0.0,
                    0.0
            );
        };
    }

    /**
     * Builds a bow profile from Minecraft's normalized draw power.
     *
     * <p>The caller should use the result of the vanilla bow draw curve. A
     * value below {@value #MINIMUM_BOW_DRAW} retains a short visible preview,
     * matching the existing overlay behaviour.</p>
     */
    public static ProjectileParameters bowParameters(double drawPower) {
        if (!finite(drawPower)
                || drawPower < 0.0
                || drawPower > 1.0) {
            throw new IllegalArgumentException(
                    "drawPower must be finite and between 0 and 1"
            );
        }
        return new ProjectileParameters(
                ProjectileType.BOW_ARROW,
                Math.max(MINIMUM_BOW_DRAW, drawPower) * 3.0,
                0.99,
                0.05,
                0.0,
                0.0
        );
    }

    /**
     * Simulates from a Minecraft-compatible yaw and pitch rotation.
     *
     * <p>Yaw zero points toward positive Z. Positive pitch points down.
     * Projectile-specific pitch offsets, such as the -20 degree offset used
     * by potions and experience bottles, are applied here.</p>
     */
    public static Result simulateFromRotation(
            Vector3 origin,
            double yawDegrees,
            double pitchDegrees,
            ProjectileParameters projectile,
            Limits limits,
            CollisionQuery collisionQuery
    ) {
        return simulateFromRotation(
                origin,
                yawDegrees,
                pitchDegrees,
                Vector3.ZERO,
                projectile,
                limits,
                collisionQuery
        );
    }

    /**
     * Simulates from rotation while inheriting the shooter's current motion.
     * Minecraft adds horizontal shooter motion to launched projectiles and
     * also adds vertical motion while the shooter is airborne.
     */
    public static Result simulateFromRotation(
            Vector3 origin,
            double yawDegrees,
            double pitchDegrees,
            Vector3 inheritedVelocity,
            ProjectileParameters projectile,
            Limits limits,
            CollisionQuery collisionQuery
    ) {
        if (!finite(yawDegrees) || !finite(pitchDegrees)) {
            return Result.invalid(origin);
        }
        if (projectile == null || !projectile.valid()) {
            return Result.invalid(origin);
        }
        Vector3 direction = directionFromRotation(
                yawDegrees,
                pitchDegrees + projectile.pitchOffsetDegrees()
        );
        return simulate(
                origin,
                direction,
                inheritedVelocity,
                projectile,
                limits,
                collisionQuery
        );
    }

    /**
     * Simulates a trajectory from an arbitrary direction.
     *
     * <p>The direction is normalized before the projectile speed is applied.
     * This overload intentionally does not apply the profile's pitch offset;
     * callers that supply a raw player rotation should use
     * {@link #simulateFromRotation}.</p>
     */
    public static Result simulate(
            Vector3 origin,
            Vector3 direction,
            ProjectileParameters projectile,
            Limits limits,
            CollisionQuery collisionQuery
    ) {
        return simulate(
                origin,
                direction,
                Vector3.ZERO,
                projectile,
                limits,
                collisionQuery
        );
    }

    /**
     * Simulates from an arbitrary direction with a bounded, finite inherited
     * launch velocity.
     */
    public static Result simulate(
            Vector3 origin,
            Vector3 direction,
            Vector3 inheritedVelocity,
            ProjectileParameters projectile,
            Limits limits,
            CollisionQuery collisionQuery
    ) {
        if (origin == null
                || direction == null
                || inheritedVelocity == null
                || projectile == null
                || limits == null
                || collisionQuery == null
                || !origin.finite()
                || !direction.finite()
                || !inheritedVelocity.finite()
                || direction.lengthSquared() <= EPSILON
                || inheritedVelocity.lengthSquared() > 1_024.0
                || !projectile.valid()) {
            return Result.invalid(origin);
        }

        Vector3 velocity = direction.normalized()
                .scale(projectile.speed())
                .add(inheritedVelocity);
        if (!velocity.finite()) {
            return Result.invalid(origin);
        }

        List<Segment> segments = new ArrayList<>(
                Math.min(limits.maximumSteps(), 128)
        );
        Vector3 position = origin;
        double travelled = 0.0;

        for (int step = 0; step < limits.maximumSteps(); step++) {
            double remaining = limits.maximumRange() - travelled;
            if (remaining <= EPSILON) {
                return result(
                        segments,
                        Optional.empty(),
                        Termination.RANGE_LIMIT,
                        travelled,
                        position,
                        velocity
                );
            }

            double tickDistance = velocity.length();
            if (!finite(tickDistance) || tickDistance <= EPSILON) {
                return result(
                        segments,
                        Optional.empty(),
                        Termination.STALLED,
                        travelled,
                        position,
                        velocity
                );
            }

            boolean rangeClipped = tickDistance > remaining;
            double segmentDistance = Math.min(tickDistance, remaining);
            Vector3 candidateEnd = position.add(
                    velocity.scale(segmentDistance / tickDistance)
            );
            if (!candidateEnd.finite()) {
                return result(
                        segments,
                        Optional.empty(),
                        Termination.INVALID_INPUT,
                        travelled,
                        position,
                        velocity
                );
            }

            Collision collision;
            try {
                collision = collisionQuery.trace(
                        position,
                        candidateEnd
                );
            } catch (RuntimeException exception) {
                return result(
                        segments,
                        Optional.empty(),
                        Termination.COLLISION_QUERY_ERROR,
                        travelled,
                        position,
                        velocity
                );
            }
            if (collision == null || !collision.valid()) {
                return result(
                        segments,
                        Optional.empty(),
                        Termination.INVALID_COLLISION,
                        travelled,
                        position,
                        velocity
                );
            }

            if (collision.kind() != CollisionKind.NONE) {
                Vector3 hitPosition = position.lerp(
                        candidateEnd,
                        collision.fraction()
                );
                double hitDistance =
                        segmentDistance * collision.fraction();
                segments.add(new Segment(
                        step,
                        position,
                        hitPosition,
                        velocity
                ));
                travelled += hitDistance;
                Impact impact = new Impact(
                        collision.kind(),
                        hitPosition,
                        step,
                        travelled
                );
                return result(
                        segments,
                        Optional.of(impact),
                        Termination.COLLISION,
                        travelled,
                        hitPosition,
                        velocity
                );
            }

            segments.add(new Segment(
                    step,
                    position,
                    candidateEnd,
                    velocity
            ));
            travelled += segmentDistance;
            position = candidateEnd;
            if (rangeClipped
                    || limits.maximumRange() - travelled <= EPSILON) {
                return result(
                        segments,
                        Optional.empty(),
                        Termination.RANGE_LIMIT,
                        travelled,
                        position,
                        velocity
                );
            }

            velocity = advanceVelocity(velocity, projectile);
            if (!velocity.finite()) {
                return result(
                        segments,
                        Optional.empty(),
                        Termination.INVALID_INPUT,
                        travelled,
                        position,
                        velocity
                );
            }
        }

        return result(
                segments,
                Optional.empty(),
                Termination.STEP_LIMIT,
                travelled,
                position,
                velocity
        );
    }

    static Vector3 advanceVelocity(
            Vector3 velocity,
            ProjectileParameters projectile
    ) {
        Vector3 dragged = velocity.scale(projectile.drag())
                .add(new Vector3(
                        0.0,
                        -projectile.gravity(),
                        0.0
                ));
        if (projectile.forwardAcceleration() <= EPSILON) {
            return dragged;
        }
        double speed = dragged.length();
        if (!finite(speed) || speed <= EPSILON) {
            return dragged;
        }
        return dragged.add(
                dragged.scale(projectile.forwardAcceleration() / speed)
        );
    }

    static Vector3 directionFromRotation(
            double yawDegrees,
            double pitchDegrees
    ) {
        if (!finite(yawDegrees) || !finite(pitchDegrees)) {
            return new Vector3(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN
            );
        }
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double pitchCosine = Math.cos(pitch);
        return new Vector3(
                -Math.sin(yaw) * pitchCosine,
                -Math.sin(pitch),
                Math.cos(yaw) * pitchCosine
        );
    }

    private static Result result(
            List<Segment> segments,
            Optional<Impact> impact,
            Termination termination,
            double travelledDistance,
            Vector3 finalPosition,
            Vector3 finalVelocity
    ) {
        return new Result(
                List.copyOf(segments),
                impact,
                termination,
                segments.size(),
                travelledDistance,
                finalPosition,
                finalVelocity
        );
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    public enum ProjectileType {
        BOW_ARROW,
        CROSSBOW_ARROW,
        CROSSBOW_FIREWORK,
        TRIDENT,
        SNOWBALL,
        EGG,
        ENDER_PEARL,
        SPLASH_POTION,
        LINGERING_POTION,
        EXPERIENCE_BOTTLE,
        WIND_CHARGE
    }

    public enum CollisionKind {
        NONE,
        BLOCK,
        ENTITY,
        FLUID
    }

    public enum Termination {
        COLLISION,
        RANGE_LIMIT,
        STEP_LIMIT,
        STALLED,
        INVALID_INPUT,
        INVALID_COLLISION,
        COLLISION_QUERY_ERROR
    }

    @FunctionalInterface
    public interface CollisionQuery {
        /**
         * Returns the first hit on the closed segment from {@code start} to
         * {@code end}. The callback is invoked at most once per simulated
         * step.
         */
        Collision trace(Vector3 start, Vector3 end);
    }

    public record Vector3(double x, double y, double z) {
        public static final Vector3 ZERO = new Vector3(0.0, 0.0, 0.0);

        public boolean finite() {
            return TrajectoryDecisionEngine26.finite(x)
                    && TrajectoryDecisionEngine26.finite(y)
                    && TrajectoryDecisionEngine26.finite(z);
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
            return new Vector3(
                    x * factor,
                    y * factor,
                    z * factor
            );
        }

        public Vector3 lerp(Vector3 other, double fraction) {
            Objects.requireNonNull(other, "other");
            return new Vector3(
                    x + (other.x - x) * fraction,
                    y + (other.y - y) * fraction,
                    z + (other.z - z) * fraction
            );
        }

        public double lengthSquared() {
            return x * x + y * y + z * z;
        }

        public double length() {
            return Math.sqrt(lengthSquared());
        }

        public Vector3 normalized() {
            double length = length();
            if (!finite() || !TrajectoryDecisionEngine26.finite(length)
                    || length <= EPSILON) {
                return new Vector3(
                        Double.NaN,
                        Double.NaN,
                        Double.NaN
                );
            }
            return scale(1.0 / length);
        }
    }

    /**
     * Physics parameters expressed in blocks and client ticks.
     */
    public record ProjectileParameters(
            ProjectileType type,
            double speed,
            double drag,
            double gravity,
            double pitchOffsetDegrees,
        double forwardAcceleration
    ) {
        public ProjectileParameters {
            Objects.requireNonNull(type, "type");
            if (!finite(speed)
                    || speed <= EPSILON
                    || speed > 10.0) {
                throw new IllegalArgumentException(
                        "speed must be finite, greater than "
                                + EPSILON + ", and at most 10"
                );
            }
            requireFiniteRange(drag, 0.0, 1.0, "drag");
            requireFiniteRange(gravity, 0.0, 2.0, "gravity");
            requireFiniteRange(
                    pitchOffsetDegrees,
                    -90.0,
                    90.0,
                    "pitchOffsetDegrees"
            );
            requireFiniteRange(
                    forwardAcceleration,
                    0.0,
                    1.0,
                    "forwardAcceleration"
            );
        }

        boolean valid() {
            return type != null
                    && finite(speed)
                    && speed > EPSILON
                    && speed <= 10.0
                    && finite(drag)
                    && drag >= 0.0
                    && drag <= 1.0
                    && finite(gravity)
                    && gravity >= 0.0
                    && gravity <= 2.0
                    && finite(pitchOffsetDegrees)
                    && pitchOffsetDegrees >= -90.0
                    && pitchOffsetDegrees <= 90.0
                    && finite(forwardAcceleration)
                    && forwardAcceleration >= 0.0
                    && forwardAcceleration <= 1.0;
        }
    }

    public record Limits(int maximumSteps, double maximumRange) {
        public static final Limits DEFAULT = new Limits(120, 96.0);

        public Limits {
            if (maximumSteps < 1
                    || maximumSteps > HARD_MAXIMUM_STEPS) {
                throw new IllegalArgumentException(
                        "maximumSteps must be between 1 and "
                                + HARD_MAXIMUM_STEPS
                );
            }
            if (!finite(maximumRange)
                    || maximumRange <= 0.0
                    || maximumRange > HARD_MAXIMUM_RANGE) {
                throw new IllegalArgumentException(
                        "maximumRange must be finite, positive, and at most "
                                + HARD_MAXIMUM_RANGE
                );
            }
        }
    }

    /**
     * Collision at a normalized fraction of the queried segment.
     */
    public record Collision(CollisionKind kind, double fraction) {
        public static Collision miss() {
            return MISS;
        }

        public static Collision hit(
                CollisionKind kind,
                double fraction
        ) {
            if (kind == null || kind == CollisionKind.NONE) {
                throw new IllegalArgumentException(
                        "a hit requires a concrete collision kind"
                );
            }
            if (!finite(fraction)
                    || fraction < 0.0
                    || fraction > 1.0) {
                throw new IllegalArgumentException(
                        "collision fraction must be between 0 and 1"
                );
            }
            return new Collision(kind, fraction);
        }

        boolean valid() {
            if (kind == null || !finite(fraction)) {
                return false;
            }
            if (kind == CollisionKind.NONE) {
                return Math.abs(fraction - 1.0) <= EPSILON;
            }
            return fraction >= 0.0 && fraction <= 1.0;
        }
    }

    public record Segment(
            int step,
            Vector3 start,
            Vector3 end,
            Vector3 velocity
    ) {
    }

    public record Impact(
            CollisionKind kind,
            Vector3 position,
            int step,
            double travelledDistance
    ) {
    }

    public record Result(
            List<Segment> segments,
            Optional<Impact> impact,
            Termination termination,
            int simulatedSteps,
            double travelledDistance,
            Vector3 finalPosition,
            Vector3 finalVelocity
    ) {
        public Result {
            segments = List.copyOf(segments);
            impact = Objects.requireNonNull(impact, "impact");
            Objects.requireNonNull(termination, "termination");
            Objects.requireNonNull(finalPosition, "finalPosition");
            Objects.requireNonNull(finalVelocity, "finalVelocity");
        }

        static Result invalid(Vector3 origin) {
            Vector3 safeOrigin =
                    origin != null && origin.finite()
                            ? origin
                            : Vector3.ZERO;
            return new Result(
                    List.of(),
                    Optional.empty(),
                    Termination.INVALID_INPUT,
                    0,
                    0.0,
                    safeOrigin,
                    Vector3.ZERO
            );
        }
    }

    private static void requireFiniteRange(
            double value,
            double minimum,
            double maximum,
            String name
    ) {
        if (!finite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be finite and between "
                            + minimum + " and " + maximum
            );
        }
    }
}
