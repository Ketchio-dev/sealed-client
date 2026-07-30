package dev.sealedclient.v26.movement;

import java.util.Objects;

/**
 * Deterministic policy for the 26.2 No Fall, Fast Swim and Jesus assists.
 *
 * <p>The policy has no Minecraft dependency. Live code supplies already
 * observed collision, fluid, input and network-safety state. Every invalid or
 * ambiguous observation fails closed; the engine never manufactures ground
 * state and never requests a movement packet by itself.</p>
 */
public final class FallWaterMovementDecisionEngine26 {
    private static final double INPUT_EPSILON = 1.0E-6;
    private static final double VELOCITY_EPSILON = 1.0E-6;
    private static final double MAXIMUM_OBSERVED_HORIZONTAL_SPEED = 2.0;
    private static final double MAXIMUM_OBSERVED_VERTICAL_SPEED = 3.0;

    private FallWaterMovementDecisionEngine26() {
    }

    public static NoFallDecision decideNoFall(
            NoFallObservation observation,
            NoFallLimits limits
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(limits, "limits");

        if (!observation.enabled()) {
            return NoFallDecision.none(BlockReason.DISABLED);
        }
        if (!finite(
                observation.fallDistance(),
                observation.verticalVelocity()
        )) {
            return NoFallDecision.none(BlockReason.INVALID_OBSERVATION);
        }
        if (observation.onGround()
                || observation.inWater()
                || observation.inLava()) {
            return new NoFallDecision(
                    NoFallDirective.RESET_ATTEMPT,
                    BlockReason.SAFE_SURFACE
            );
        }
        if (!observation.safetyReady()) {
            return NoFallDecision.none(BlockReason.NETWORK_SUPPRESSED);
        }
        if (observation.manualJump() || observation.manualShift()) {
            return NoFallDecision.none(BlockReason.MANUAL_OVERRIDE);
        }
        if (observation.attemptedThisFall()) {
            return NoFallDecision.none(BlockReason.ALREADY_ATTEMPTED);
        }
        if (!observation.packetBudgetReady()) {
            return NoFallDecision.none(BlockReason.ACTION_BUDGET);
        }
        if (observation.fallFlying()) {
            return NoFallDecision.none(BlockReason.ALREADY_GLIDING);
        }
        if (observation.passenger()
                || observation.climbable()
                || observation.noGravity()
                || observation.abilitiesFlying()
                || observation.verticalCollision()) {
            return NoFallDecision.none(BlockReason.UNSAFE_MOVEMENT_STATE);
        }
        if (!observation.chestGlideUsable()) {
            return NoFallDecision.none(BlockReason.NO_SAFE_ELYTRA);
        }
        if (observation.fallDistance() < limits.triggerDistance()
                || observation.verticalVelocity()
                >= -limits.minimumDescentSpeed()) {
            return NoFallDecision.none(BlockReason.NOT_FALLING_FAST_ENOUGH);
        }
        return new NoFallDecision(
                NoFallDirective.ATTEMPT_GLIDE,
                BlockReason.NONE
        );
    }

    public static VelocityDecision decideFastSwim(
            FastSwimObservation observation,
            FastSwimLimits limits
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(limits, "limits");

        BlockReason common = validateWaterAssist(
                observation.enabled(),
                observation.safetyScale(),
                observation.inWater(),
                observation.inLava(),
                observation.passenger(),
                observation.fallFlying(),
                observation.noGravity()
        );
        if (common != BlockReason.NONE) {
            return VelocityDecision.none(common);
        }
        if (!finite(
                observation.inputStrafe(),
                observation.inputForward(),
                observation.yawDegrees(),
                observation.currentX(),
                observation.currentY(),
                observation.currentZ()
        )) {
            return VelocityDecision.none(BlockReason.INVALID_OBSERVATION);
        }
        if (observation.horizontalCollision()
                || !observation.horizontalPathClear()) {
            return VelocityDecision.none(BlockReason.COLLISION_RISK);
        }

        double inputLength = Math.hypot(
                observation.inputStrafe(),
                observation.inputForward()
        );
        if (inputLength <= INPUT_EPSILON) {
            return VelocityDecision.none(BlockReason.NO_MANUAL_INPUT);
        }
        double strafe = observation.inputStrafe()
                / Math.max(1.0, inputLength);
        double forward = observation.inputForward()
                / Math.max(1.0, inputLength);
        double yawRadians = Math.toRadians(observation.yawDegrees());
        double sin = Math.sin(yawRadians);
        double cos = Math.cos(yawRadians);
        double directionX = strafe * cos - forward * sin;
        double directionZ = forward * cos + strafe * sin;
        double directionLength = Math.hypot(directionX, directionZ);
        if (!Double.isFinite(directionLength)
                || directionLength <= INPUT_EPSILON) {
            return VelocityDecision.none(BlockReason.INVALID_OBSERVATION);
        }
        directionX /= directionLength;
        directionZ /= directionLength;

        double scale = boundedScale(observation.safetyScale());
        double targetSpeed = limits.targetSpeed() * scale;
        double blend = limits.accelerationBlend() * scale;
        double nextX = observation.currentX()
                + (directionX * targetSpeed - observation.currentX()) * blend;
        double nextZ = observation.currentZ()
                + (directionZ * targetSpeed - observation.currentZ()) * blend;

        double currentHorizontal = Math.hypot(
                observation.currentX(),
                observation.currentZ()
        );
        if (currentHorizontal > MAXIMUM_OBSERVED_HORIZONTAL_SPEED
                || Math.abs(observation.currentY())
                > MAXIMUM_OBSERVED_VERTICAL_SPEED) {
            return VelocityDecision.none(BlockReason.UNSAFE_MOVEMENT_STATE);
        }
        double gradualLimit = Math.max(
                targetSpeed,
                currentHorizontal - limits.targetSpeed() * blend
        );
        double nextHorizontal = Math.hypot(nextX, nextZ);
        if (nextHorizontal > gradualLimit
                && nextHorizontal > VELOCITY_EPSILON) {
            double cap = gradualLimit / nextHorizontal;
            nextX *= cap;
            nextZ *= cap;
        }
        if (!finite(nextX, observation.currentY(), nextZ)) {
            return VelocityDecision.none(BlockReason.INVALID_OBSERVATION);
        }
        if (Math.abs(nextX - observation.currentX()) <= VELOCITY_EPSILON
                && Math.abs(nextZ - observation.currentZ())
                <= VELOCITY_EPSILON) {
            return VelocityDecision.none(BlockReason.ALREADY_AT_TARGET);
        }
        return new VelocityDecision(
                true,
                BlockReason.NONE,
                nextX,
                observation.currentY(),
                nextZ
        );
    }

    public static VelocityDecision decideJesus(
            JesusObservation observation,
            JesusLimits limits
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(limits, "limits");

        BlockReason common = validateWaterAssist(
                observation.enabled(),
                observation.safetyScale(),
                observation.inWater(),
                observation.inLava(),
                observation.passenger(),
                observation.fallFlying(),
                observation.noGravity()
        );
        if (common != BlockReason.NONE) {
            return VelocityDecision.none(common);
        }
        if (!finite(
                observation.currentX(),
                observation.currentY(),
                observation.currentZ()
        )) {
            return VelocityDecision.none(BlockReason.INVALID_OBSERVATION);
        }
        if (Math.hypot(
                observation.currentX(),
                observation.currentZ()
        ) > MAXIMUM_OBSERVED_HORIZONTAL_SPEED
                || Math.abs(observation.currentY())
                > MAXIMUM_OBSERVED_VERTICAL_SPEED) {
            return VelocityDecision.none(BlockReason.UNSAFE_MOVEMENT_STATE);
        }
        if (observation.underWater()
                || !observation.stableWaterSurface()
                || observation.bubbleColumn()) {
            return VelocityDecision.none(BlockReason.NOT_SAFE_SURFACE);
        }
        if (observation.manualShift()) {
            return VelocityDecision.none(BlockReason.MANUAL_OVERRIDE);
        }
        if (observation.verticalCollisionAbove()
                || !observation.upwardPathClear()) {
            return VelocityDecision.none(BlockReason.COLLISION_RISK);
        }

        double scale = boundedScale(observation.safetyScale());
        double targetY = limits.targetBuoyancy() * scale;
        if (observation.currentY() >= targetY - VELOCITY_EPSILON) {
            return VelocityDecision.none(BlockReason.ALREADY_AT_TARGET);
        }
        double nextY = Math.min(
                targetY,
                observation.currentY()
                        + limits.maximumUpwardAcceleration() * scale
        );
        if (!Double.isFinite(nextY)
                || nextY <= observation.currentY() + VELOCITY_EPSILON) {
            return VelocityDecision.none(BlockReason.INVALID_OBSERVATION);
        }
        return new VelocityDecision(
                true,
                BlockReason.NONE,
                observation.currentX(),
                nextY,
                observation.currentZ()
        );
    }

    private static BlockReason validateWaterAssist(
            boolean enabled,
            double safetyScale,
            boolean inWater,
            boolean inLava,
            boolean passenger,
            boolean fallFlying,
            boolean noGravity
    ) {
        if (!enabled) {
            return BlockReason.DISABLED;
        }
        if (!Double.isFinite(safetyScale)
                || boundedScale(safetyScale) <= 0.0) {
            return BlockReason.NETWORK_SUPPRESSED;
        }
        if (!inWater || inLava) {
            return BlockReason.NOT_IN_SAFE_WATER;
        }
        if (passenger || fallFlying || noGravity) {
            return BlockReason.UNSAFE_MOVEMENT_STATE;
        }
        return BlockReason.NONE;
    }

    private static double boundedScale(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    public enum NoFallDirective {
        NONE,
        RESET_ATTEMPT,
        ATTEMPT_GLIDE
    }

    public enum BlockReason {
        NONE,
        DISABLED,
        INVALID_OBSERVATION,
        SAFE_SURFACE,
        NETWORK_SUPPRESSED,
        MANUAL_OVERRIDE,
        ALREADY_ATTEMPTED,
        ACTION_BUDGET,
        ALREADY_GLIDING,
        UNSAFE_MOVEMENT_STATE,
        NO_SAFE_ELYTRA,
        NOT_FALLING_FAST_ENOUGH,
        NOT_IN_SAFE_WATER,
        NO_MANUAL_INPUT,
        COLLISION_RISK,
        NOT_SAFE_SURFACE,
        ALREADY_AT_TARGET
    }

    public record NoFallDecision(
            NoFallDirective directive,
            BlockReason reason
    ) {
        public NoFallDecision {
            Objects.requireNonNull(directive, "directive");
            Objects.requireNonNull(reason, "reason");
        }

        public boolean shouldAttempt() {
            return directive == NoFallDirective.ATTEMPT_GLIDE;
        }

        public boolean shouldResetAttempt() {
            return directive == NoFallDirective.RESET_ATTEMPT;
        }

        static NoFallDecision none(BlockReason reason) {
            return new NoFallDecision(NoFallDirective.NONE, reason);
        }
    }

    public record VelocityDecision(
            boolean apply,
            BlockReason reason,
            double x,
            double y,
            double z
    ) {
        public VelocityDecision {
            Objects.requireNonNull(reason, "reason");
            if (apply && !finite(x, y, z)) {
                throw new IllegalArgumentException(
                        "Applied velocity must be finite"
                );
            }
        }

        static VelocityDecision none(BlockReason reason) {
            return new VelocityDecision(false, reason, 0.0, 0.0, 0.0);
        }
    }

    public record NoFallLimits(
            double triggerDistance,
            double minimumDescentSpeed
    ) {
        public NoFallLimits {
            if (!Double.isFinite(triggerDistance)
                    || triggerDistance < 2.5
                    || triggerDistance > 10.0) {
                throw new IllegalArgumentException(
                        "No Fall trigger distance must be in [2.5, 10.0]"
                );
            }
            if (!Double.isFinite(minimumDescentSpeed)
                    || minimumDescentSpeed < 0.01
                    || minimumDescentSpeed > 1.0) {
                throw new IllegalArgumentException(
                        "Minimum descent speed must be in [0.01, 1.0]"
                );
            }
        }
    }

    public record FastSwimLimits(
            double targetSpeed,
            double accelerationBlend
    ) {
        public FastSwimLimits {
            if (!Double.isFinite(targetSpeed)
                    || targetSpeed < 0.12
                    || targetSpeed > 0.36) {
                throw new IllegalArgumentException(
                        "Fast Swim target speed must be in [0.12, 0.36]"
                );
            }
            if (!Double.isFinite(accelerationBlend)
                    || accelerationBlend < 0.05
                    || accelerationBlend > 0.5) {
                throw new IllegalArgumentException(
                        "Fast Swim blend must be in [0.05, 0.5]"
                );
            }
        }
    }

    public record JesusLimits(
            double targetBuoyancy,
            double maximumUpwardAcceleration
    ) {
        public JesusLimits {
            if (!Double.isFinite(targetBuoyancy)
                    || targetBuoyancy < 0.02
                    || targetBuoyancy > 0.12) {
                throw new IllegalArgumentException(
                        "Jesus buoyancy must be in [0.02, 0.12]"
                );
            }
            if (!Double.isFinite(maximumUpwardAcceleration)
                    || maximumUpwardAcceleration < 0.005
                    || maximumUpwardAcceleration > 0.08) {
                throw new IllegalArgumentException(
                        "Jesus acceleration must be in [0.005, 0.08]"
                );
            }
        }
    }

    public record NoFallObservation(
            boolean enabled,
            boolean safetyReady,
            boolean onGround,
            boolean inWater,
            boolean inLava,
            boolean fallFlying,
            boolean passenger,
            boolean climbable,
            boolean noGravity,
            boolean abilitiesFlying,
            boolean verticalCollision,
            boolean manualJump,
            boolean manualShift,
            boolean chestGlideUsable,
            boolean attemptedThisFall,
            boolean packetBudgetReady,
            double fallDistance,
            double verticalVelocity
    ) {
    }

    public record FastSwimObservation(
            boolean enabled,
            double safetyScale,
            boolean inWater,
            boolean inLava,
            boolean passenger,
            boolean fallFlying,
            boolean noGravity,
            boolean horizontalCollision,
            boolean horizontalPathClear,
            double inputStrafe,
            double inputForward,
            double yawDegrees,
            double currentX,
            double currentY,
            double currentZ
    ) {
    }

    public record JesusObservation(
            boolean enabled,
            double safetyScale,
            boolean inWater,
            boolean underWater,
            boolean inLava,
            boolean stableWaterSurface,
            boolean bubbleColumn,
            boolean manualShift,
            boolean passenger,
            boolean fallFlying,
            boolean noGravity,
            boolean verticalCollisionAbove,
            boolean upwardPathClear,
            double currentX,
            double currentY,
            double currentZ
    ) {
    }
}
