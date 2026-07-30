package dev.sealedclient.v26.movement;

import java.util.Objects;

/**
 * Pure bounded velocity and pitch state machine for Elytra Control.
 *
 * <p>The caller must provide the shared {@link MovementSafetyPolicy26}
 * decision as {@code safetyAllowed/safetyScale}. The engine does not maintain
 * a second latency policy. It approaches requested horizontal and vertical
 * targets by a strict per-tick budget and never starts flight.</p>
 *
 * <p>Pitch is owned only after a committed decision. If the observed pitch
 * differs from the last committed pitch beyond a configurable threshold, the
 * player is considered to have moved the camera manually and pitch automation
 * yields for a bounded interval while directional velocity assistance may
 * continue.</p>
 */
public final class ElytraControlDecisionEngine26 {
    private static final double INPUT_EPSILON = 1.0E-4;
    private static final double VELOCITY_EPSILON = 1.0E-9;

    private long sessionKey = Long.MIN_VALUE;
    private long sequence;
    private boolean pitchOwnedLastTick;
    private float lastAppliedPitch;
    private int manualPitchSuppressionTicks;
    private Decision outstanding = Decision.blocked(
            0L,
            BlockReason.SESSION
    );

    public Decision decide(
            Observation observation,
            Configuration configuration
    ) {
        sequence++;
        if (observation == null
                || configuration == null
                || !observation.valid()) {
            pitchOwnedLastTick = false;
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.INVALID
            );
            return outstanding;
        }
        if (observation.sessionKey() != sessionKey) {
            resetForSession(observation.sessionKey());
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.SESSION_WARMUP
            );
            return outstanding;
        }
        if (!observation.enabled()) {
            clearTransientOwnership();
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.DISABLED
            );
            return outstanding;
        }
        if (!observation.sessionReady()) {
            reset();
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.SESSION
            );
            return outstanding;
        }
        if (!observation.screenClear()) {
            pitchOwnedLastTick = false;
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.SCREEN
            );
            return outstanding;
        }
        if (!observation.fallFlying()) {
            clearTransientOwnership();
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.NOT_GLIDING
            );
            return outstanding;
        }
        if (observation.passenger()
                || observation.inWater()
                || observation.inLava()
                || observation.horizontalCollision()) {
            clearTransientOwnership();
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.UNSAFE_MOVEMENT_STATE
            );
            return outstanding;
        }
        if (!observation.safetyAllowed()
                || observation.safetyScale() <= 0.0) {
            pitchOwnedLastTick = false;
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.NETWORK_SAFETY
            );
            return outstanding;
        }

        observeManualPitch(
                observation.pitchDegrees(),
                configuration
        );
        if (manualPitchSuppressionTicks > 0) {
            manualPitchSuppressionTicks--;
        }
        pitchOwnedLastTick = false;

        double inputLength = Math.hypot(
                observation.inputStrafe(),
                observation.inputForward()
        );
        boolean horizontalRequested = inputLength > INPUT_EPSILON;
        boolean verticalRequested =
                observation.ascend() != observation.descend();
        if (!horizontalRequested && !verticalRequested) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.NO_INPUT
            );
            return outstanding;
        }

        double scale = Math.min(1.0, observation.safetyScale());
        double acceleration =
                configuration.accelerationPerTick() * scale;
        double nextX = observation.velocityX();
        double nextZ = observation.velocityZ();
        if (horizontalRequested) {
            double normalize = 1.0 / Math.max(1.0, inputLength);
            double strafe = observation.inputStrafe() * normalize;
            double forward = observation.inputForward() * normalize;
            double yaw = Math.toRadians(observation.yawDegrees());
            double directionX =
                    strafe * Math.cos(yaw) - forward * Math.sin(yaw);
            double directionZ =
                    forward * Math.cos(yaw) + strafe * Math.sin(yaw);
            double directionLength = Math.hypot(
                    directionX,
                    directionZ
            );
            if (!Double.isFinite(directionLength)
                    || directionLength <= INPUT_EPSILON) {
                outstanding = Decision.blocked(
                        sequence,
                        BlockReason.INVALID
                );
                return outstanding;
            }
            double targetSpeed = configuration.cruiseSpeed() * scale;
            directionX = directionX / directionLength * targetSpeed;
            directionZ = directionZ / directionLength * targetSpeed;
            double[] approached = approachVector(
                    observation.velocityX(),
                    observation.velocityZ(),
                    directionX,
                    directionZ,
                    acceleration
            );
            nextX = approached[0];
            nextZ = approached[1];
        }

        double nextY = observation.velocityY();
        if (verticalRequested) {
            double targetY = configuration.verticalSpeed()
                    * scale
                    * (observation.ascend() ? 1.0 : -1.0);
            nextY = approach(
                    observation.velocityY(),
                    targetY,
                    acceleration
            );
        }

        boolean pitchRequested = verticalRequested
                && manualPitchSuppressionTicks == 0;
        float nextPitch = observation.pitchDegrees();
        if (pitchRequested) {
            double targetPitch = observation.ascend()
                    ? configuration.climbPitchDegrees()
                    : configuration.descentPitchDegrees();
            nextPitch = (float) approach(
                    observation.pitchDegrees(),
                    targetPitch,
                    configuration.maximumPitchChangePerTick() * scale
            );
            nextPitch = Math.max(-90.0F, Math.min(90.0F, nextPitch));
        }

        if (!finite(nextX, nextY, nextZ, nextPitch)) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.INVALID
            );
            return outstanding;
        }
        boolean horizontalChange = horizontalRequested
                && (Math.abs(nextX - observation.velocityX())
                > VELOCITY_EPSILON
                || Math.abs(nextZ - observation.velocityZ())
                > VELOCITY_EPSILON);
        boolean verticalChange = verticalRequested
                && Math.abs(nextY - observation.velocityY())
                > VELOCITY_EPSILON;
        boolean pitchChange = pitchRequested
                && Math.abs(nextPitch - observation.pitchDegrees())
                > 1.0E-5F;
        if (!horizontalChange && !verticalChange && !pitchChange) {
            outstanding = Decision.blocked(
                    sequence,
                    BlockReason.AT_TARGET
            );
            return outstanding;
        }

        outstanding = new Decision(
                sequence,
                true,
                horizontalChange,
                verticalChange,
                pitchChange,
                nextX,
                nextY,
                nextZ,
                nextPitch,
                acceleration,
                scale,
                manualPitchSuppressionTicks,
                BlockReason.NONE
        );
        return outstanding;
    }

    /**
     * Records pitch ownership only for the latest action actually executed.
     */
    public void commit(Decision decision, boolean executed) {
        if (decision == null
                || decision.sequence() != outstanding.sequence()
                || !decision.apply()
                || decision != outstanding) {
            return;
        }
        if (executed && decision.applyPitch()) {
            pitchOwnedLastTick = true;
            lastAppliedPitch = decision.nextPitchDegrees();
        } else {
            pitchOwnedLastTick = false;
        }
    }

    public void reset() {
        sessionKey = Long.MIN_VALUE;
        sequence = 0L;
        clearTransientOwnership();
        outstanding = Decision.blocked(0L, BlockReason.SESSION);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                sessionKey != Long.MIN_VALUE,
                pitchOwnedLastTick,
                lastAppliedPitch,
                manualPitchSuppressionTicks
        );
    }

    static double[] approachVector(
            double currentX,
            double currentZ,
            double targetX,
            double targetZ,
            double maximumChange
    ) {
        if (!finite(
                currentX,
                currentZ,
                targetX,
                targetZ,
                maximumChange
        ) || maximumChange < 0.0) {
            throw new IllegalArgumentException(
                    "Vector approach requires finite values and non-negative budget"
            );
        }
        double deltaX = targetX - currentX;
        double deltaZ = targetZ - currentZ;
        double length = Math.hypot(deltaX, deltaZ);
        if (length <= maximumChange || length <= INPUT_EPSILON) {
            return new double[]{targetX, targetZ};
        }
        double scale = maximumChange / length;
        return new double[]{
                currentX + deltaX * scale,
                currentZ + deltaZ * scale
        };
    }

    static double approach(
            double current,
            double target,
            double maximumChange
    ) {
        if (!finite(current, target, maximumChange)
                || maximumChange < 0.0) {
            throw new IllegalArgumentException(
                    "Approach requires finite values and non-negative budget"
            );
        }
        if (current < target) {
            return Math.min(current + maximumChange, target);
        }
        return Math.max(current - maximumChange, target);
    }

    private void observeManualPitch(
            float observedPitch,
            Configuration configuration
    ) {
        if (!pitchOwnedLastTick) {
            return;
        }
        double difference = Math.abs(
                wrapDegrees(observedPitch - lastAppliedPitch)
        );
        if (difference
                > configuration.manualPitchOverrideDegrees()) {
            manualPitchSuppressionTicks = Math.max(
                    manualPitchSuppressionTicks,
                    configuration.manualPitchSuppressionTicks()
            );
        }
    }

    private void resetForSession(long newSessionKey) {
        sessionKey = newSessionKey;
        clearTransientOwnership();
    }

    private void clearTransientOwnership() {
        pitchOwnedLastTick = false;
        lastAppliedPitch = 0.0F;
        manualPitchSuppressionTicks = 0;
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
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
            double cruiseSpeed,
            double accelerationPerTick,
            double verticalSpeed,
            double maximumPitchChangePerTick,
            double climbPitchDegrees,
            double descentPitchDegrees,
            double manualPitchOverrideDegrees,
            int manualPitchSuppressionTicks
    ) {
        public static final Configuration DEFAULT = new Configuration(
                1.25,
                0.04,
                0.25,
                2.0,
                -12.0,
                22.0,
                3.0,
                8
        );

        public Configuration(
                double cruiseSpeed,
                double accelerationPerTick,
                double verticalSpeed
        ) {
            this(
                    cruiseSpeed,
                    accelerationPerTick,
                    verticalSpeed,
                    2.0,
                    -12.0,
                    22.0,
                    3.0,
                    8
            );
        }

        public Configuration {
            requireRange(cruiseSpeed, 0.40, 2.00, "cruiseSpeed");
            requireRange(
                    accelerationPerTick,
                    0.005,
                    0.12,
                    "accelerationPerTick"
            );
            requireRange(verticalSpeed, 0.05, 0.50, "verticalSpeed");
            requireRange(
                    maximumPitchChangePerTick,
                    0.25,
                    10.0,
                    "maximumPitchChangePerTick"
            );
            requireRange(
                    climbPitchDegrees,
                    -45.0,
                    0.0,
                    "climbPitchDegrees"
            );
            requireRange(
                    descentPitchDegrees,
                    0.0,
                    60.0,
                    "descentPitchDegrees"
            );
            requireRange(
                    manualPitchOverrideDegrees,
                    0.5,
                    30.0,
                    "manualPitchOverrideDegrees"
            );
            if (manualPitchSuppressionTicks < 1
                    || manualPitchSuppressionTicks > 40) {
                throw new IllegalArgumentException(
                        "manualPitchSuppressionTicks must be 1..40"
                );
            }
        }

        private static void requireRange(
                double value,
                double minimum,
                double maximum,
                String name
        ) {
            if (!Double.isFinite(value)
                    || value < minimum
                    || value > maximum) {
                throw new IllegalArgumentException(
                        name + " must be finite and in ["
                                + minimum + ", " + maximum + "]"
                );
            }
        }
    }

    public record Observation(
            long sessionKey,
            boolean enabled,
            boolean sessionReady,
            boolean screenClear,
            boolean safetyAllowed,
            double safetyScale,
            boolean fallFlying,
            boolean passenger,
            boolean inWater,
            boolean inLava,
            boolean horizontalCollision,
            double inputStrafe,
            double inputForward,
            boolean ascend,
            boolean descend,
            double yawDegrees,
            float pitchDegrees,
            double velocityX,
            double velocityY,
            double velocityZ
    ) {
        boolean valid() {
            return sessionKey != Long.MIN_VALUE
                    && finite(
                    safetyScale,
                    inputStrafe,
                    inputForward,
                    yawDegrees,
                    pitchDegrees,
                    velocityX,
                    velocityY,
                    velocityZ
            )
                    && safetyScale >= 0.0
                    && safetyScale <= 1.0;
        }
    }

    public record Decision(
            long sequence,
            boolean apply,
            boolean applyHorizontal,
            boolean applyVertical,
            boolean applyPitch,
            double nextVelocityX,
            double nextVelocityY,
            double nextVelocityZ,
            float nextPitchDegrees,
            double accelerationBudget,
            double safetyScale,
            int manualPitchSuppressionTicks,
            BlockReason blockReason
    ) {
        public Decision {
            blockReason = Objects.requireNonNull(
                    blockReason,
                    "blockReason"
            );
        }

        private static Decision blocked(
                long sequence,
                BlockReason reason
        ) {
            return new Decision(
                    sequence,
                    false,
                    false,
                    false,
                    false,
                    0.0,
                    0.0,
                    0.0,
                    0.0F,
                    0.0,
                    0.0,
                    0,
                    reason
            );
        }
    }

    public record Snapshot(
            boolean sessionObserved,
            boolean pitchOwnedLastTick,
            float lastAppliedPitch,
            int manualPitchSuppressionTicks
    ) {
    }

    public enum BlockReason {
        NONE,
        INVALID,
        SESSION,
        SESSION_WARMUP,
        DISABLED,
        SCREEN,
        NOT_GLIDING,
        UNSAFE_MOVEMENT_STATE,
        NETWORK_SAFETY,
        NO_INPUT,
        AT_TARGET
    }
}
