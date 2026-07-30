package dev.b2tclient.v26.movement;

/**
 * Pure, bounded ground-speed policy.
 *
 * <p>The engine only approaches a target velocity derived from real movement
 * input. It never invents input, changes vertical velocity, or sends movement
 * packets. The shared movement safety policy supplies a scale that already
 * accounts for latency and recent server corrections.</p>
 */
public final class GroundSpeedDecisionEngine26 {
    private static final double INPUT_EPSILON = 1.0E-4;

    public Decision decide(Observation observation, Configuration configuration) {
        if (observation == null || configuration == null) {
            return Decision.blocked(BlockReason.INVALID);
        }
        BlockReason safetyBlock = safetyBlock(observation);
        if (safetyBlock != BlockReason.NONE) {
            return Decision.blocked(safetyBlock);
        }
        if (!finite(
                observation.inputStrafe(),
                observation.inputForward(),
                observation.yawDegrees(),
                observation.currentVelocityX(),
                observation.currentVelocityZ(),
                observation.safetyScale()
        )) {
            return Decision.blocked(BlockReason.INVALID);
        }

        double inputLength = Math.hypot(
                observation.inputStrafe(),
                observation.inputForward()
        );
        if (inputLength <= INPUT_EPSILON) {
            return Decision.blocked(BlockReason.NO_INPUT);
        }
        double inputScale = 1.0 / Math.max(1.0, inputLength);
        double inputMagnitude = Math.min(1.0, inputLength);
        double strafe = observation.inputStrafe() * inputScale;
        double forward = observation.inputForward() * inputScale;
        double yaw = Math.toRadians(observation.yawDegrees());
        double directionX = strafe * Math.cos(yaw) - forward * Math.sin(yaw);
        double directionZ = forward * Math.cos(yaw) + strafe * Math.sin(yaw);
        double directionLength = Math.hypot(directionX, directionZ);
        if (!Double.isFinite(directionLength) || directionLength <= INPUT_EPSILON) {
            return Decision.blocked(BlockReason.INVALID);
        }
        directionX /= directionLength;
        directionZ /= directionLength;

        double safetyScale = Math.min(1.0, observation.safetyScale());
        double targetSpeed = configuration.targetSpeed()
                * safetyScale
                * inputMagnitude;
        double acceleration = configuration.accelerationPerTick() * safetyScale;
        double targetX = directionX * targetSpeed;
        double targetZ = directionZ * targetSpeed;
        double differenceX = targetX - observation.currentVelocityX();
        double differenceZ = targetZ - observation.currentVelocityZ();
        double differenceLength = Math.hypot(differenceX, differenceZ);
        if (differenceLength > acceleration) {
            double accelerationScale = acceleration / differenceLength;
            differenceX *= accelerationScale;
            differenceZ *= accelerationScale;
        }

        double nextX = observation.currentVelocityX() + differenceX;
        double nextZ = observation.currentVelocityZ() + differenceZ;
        if (!finite(nextX, nextZ)) {
            return Decision.blocked(BlockReason.INVALID);
        }
        return new Decision(
                true,
                nextX,
                nextZ,
                targetSpeed,
                acceleration,
                safetyScale,
                BlockReason.NONE
        );
    }

    private static BlockReason safetyBlock(Observation observation) {
        if (!observation.enabled()) {
            return BlockReason.DISABLED;
        }
        if (!observation.sessionActive()
                || !observation.playerPresent()
                || !observation.playerAlive()
                || !observation.networkReady()) {
            return BlockReason.SESSION;
        }
        if (!observation.screenClear()) {
            return BlockReason.SCREEN;
        }
        if (!observation.safetyAllowed() || observation.safetyScale() <= 0.0) {
            return BlockReason.NETWORK_SAFETY;
        }
        if (!observation.onGround()
                || observation.passenger()
                || observation.inWater()
                || observation.inLava()
                || observation.swimming()
                || observation.fallFlying()
                || observation.flying()
                || observation.horizontalCollision()) {
            return BlockReason.UNSAFE_MOVEMENT_STATE;
        }
        return BlockReason.NONE;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    public record Configuration(
            double targetSpeed,
            double accelerationPerTick
    ) {
        public static final Configuration DEFAULT = new Configuration(
                0.31,
                0.06
        );

        public Configuration {
            requireRange(targetSpeed, 0.05, 0.80, "targetSpeed");
            requireRange(accelerationPerTick, 0.001, 0.30, "accelerationPerTick");
        }

        private static void requireRange(
                double value,
                double minimum,
                double maximum,
                String name
        ) {
            if (!Double.isFinite(value) || value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        name + " must be finite and in [" + minimum + ", "
                                + maximum + "]"
                );
            }
        }
    }

    public record Observation(
            boolean enabled,
            boolean sessionActive,
            boolean playerPresent,
            boolean playerAlive,
            boolean screenClear,
            boolean networkReady,
            boolean safetyAllowed,
            double safetyScale,
            boolean onGround,
            boolean passenger,
            boolean inWater,
            boolean inLava,
            boolean swimming,
            boolean fallFlying,
            boolean flying,
            boolean horizontalCollision,
            double inputStrafe,
            double inputForward,
            double yawDegrees,
            double currentVelocityX,
            double currentVelocityZ
    ) {
        public Observation withSafety(boolean allowed, double scale) {
            return new Observation(
                    enabled,
                    sessionActive,
                    playerPresent,
                    playerAlive,
                    screenClear,
                    networkReady,
                    allowed,
                    scale,
                    onGround,
                    passenger,
                    inWater,
                    inLava,
                    swimming,
                    fallFlying,
                    flying,
                    horizontalCollision,
                    inputStrafe,
                    inputForward,
                    yawDegrees,
                    currentVelocityX,
                    currentVelocityZ
            );
        }
    }

    public record Decision(
            boolean apply,
            double nextVelocityX,
            double nextVelocityZ,
            double targetSpeed,
            double accelerationBudget,
            double safetyScale,
            BlockReason blockReason
    ) {
        private static Decision blocked(BlockReason reason) {
            return new Decision(
                    false,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    reason
            );
        }
    }

    public enum BlockReason {
        NONE,
        DISABLED,
        SESSION,
        SCREEN,
        NETWORK_SAFETY,
        UNSAFE_MOVEMENT_STATE,
        NO_INPUT,
        INVALID
    }
}
