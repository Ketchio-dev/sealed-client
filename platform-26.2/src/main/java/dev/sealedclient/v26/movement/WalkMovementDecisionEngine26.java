package dev.sealedclient.v26.movement;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft-independent decisions for Safe Walk, Auto Center, Hole Snap, and
 * Step.
 *
 * <p>The live adapter owns world reads and mutations. This engine consumes
 * immutable observations, inspects explicitly bounded candidate lists, and
 * returns finite, per-tick-capped plans. Manual directional, jump, or crouch
 * input always takes precedence over autonomous steering.</p>
 */
final class WalkMovementDecisionEngine26 {
    static final double VANILLA_STEP_HEIGHT = 0.6;
    static final double INPUT_EPSILON_SQUARED = 1.0E-6;

    private static final Comparator<HoleCandidate> HOLE_ORDER =
            Comparator.comparingDouble(HoleCandidate::distanceSquared)
                    .thenComparingDouble(HoleCandidate::verticalDistance)
                    .thenComparingLong(HoleCandidate::key);

    private WalkMovementDecisionEngine26() {
    }

    static boolean shouldStopAtEdge(EdgeObservation observation) {
        return observation != null
                && observation.control().basicControlAllowed()
                && observation.control().onGround()
                && observation.control().manualDirectional()
                && !observation.control().manualJump()
                && !observation.control().manualCrouch()
                && observation.directionFinite()
                && observation.directionLengthSquared()
                >= INPUT_EPSILON_SQUARED
                && observation.lookAhead() > 0.0
                && observation.unsupportedAhead();
    }

    /**
     * Produces a bounded autonomous horizontal step toward a target.
     *
     * <p>Autonomous steering is fail-closed while any manual movement input is
     * present. The returned vector never exceeds {@code maximumSpeed} or the
     * remaining target distance.</p>
     */
    static Optional<HorizontalPlan> steer(SteeringObservation observation) {
        if (observation == null
                || !observation.control().basicControlAllowed()
                || !observation.control().onGround()
                || observation.control().manualOverride()
                || !observation.supported()
                || !observation.pathClear()
                || !observation.finite()
                || observation.safetyScale() <= 0.0
                || observation.maximumSpeed() <= 0.0
                || observation.tolerance() < 0.0) {
            return Optional.empty();
        }

        double deltaX = observation.targetX() - observation.currentX();
        double deltaZ = observation.targetZ() - observation.currentZ();
        double distance = Math.hypot(deltaX, deltaZ);
        if (!Double.isFinite(distance)) {
            return Optional.empty();
        }
        if (distance <= observation.tolerance()) {
            return Optional.of(HorizontalPlan.stop(distance));
        }

        double cap = observation.maximumSpeed()
                * Math.min(1.0, observation.safetyScale());
        double applied = Math.min(cap, distance);
        if (!Double.isFinite(applied) || applied <= 0.0) {
            return Optional.empty();
        }
        return Optional.of(new HorizontalPlan(
                deltaX / distance * applied,
                deltaZ / distance * applied,
                distance,
                false
        ));
    }

    /**
     * Selects the nearest valid hole while inspecting at most
     * {@code maximumInspections} entries.
     */
    static HoleSelection selectHole(
            List<HoleCandidate> candidates,
            int maximumInspections,
            double maximumHorizontalDistance,
            double maximumVerticalDistance
    ) {
        if (candidates == null
                || maximumInspections <= 0
                || !finiteNonNegative(maximumHorizontalDistance)
                || !finiteNonNegative(maximumVerticalDistance)) {
            return HoleSelection.none();
        }

        int inspected = 0;
        HoleCandidate best = null;
        double maximumDistanceSquared =
                maximumHorizontalDistance * maximumHorizontalDistance;
        for (HoleCandidate candidate : candidates) {
            if (inspected >= maximumInspections) {
                break;
            }
            inspected++;
            if (candidate == null
                    || !candidate.safe()
                    || !candidate.loaded()
                    || !candidate.pathClear()
                    || candidate.distanceSquared() > maximumDistanceSquared
                    || candidate.verticalDistance() > maximumVerticalDistance) {
                continue;
            }
            if (best == null || HOLE_ORDER.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best == null
                ? new HoleSelection(Optional.empty(), inspected)
                : new HoleSelection(Optional.of(best), inspected);
    }

    /**
     * Computes a ramped Step target. Step never creates horizontal movement,
     * and manual jump/crouch input disables it for the tick.
     */
    static Optional<StepPlan> step(StepObservation observation) {
        if (observation == null
                || !observation.control().basicControlAllowed()
                || !observation.control().onGround()
                || !observation.control().manualDirectional()
                || observation.control().manualJump()
                || observation.control().manualCrouch()
                || !finiteNonNegative(observation.currentTargetHeight())
                || !Double.isFinite(observation.configuredHeight())
                || observation.configuredHeight() < VANILLA_STEP_HEIGHT
                || !Double.isFinite(observation.maximumIncreasePerTick())
                || observation.maximumIncreasePerTick() <= 0.0
                || !Double.isFinite(observation.safetyScale())
                || observation.safetyScale() <= 0.0) {
            return Optional.empty();
        }

        double scaledTarget = VANILLA_STEP_HEIGHT
                + (observation.configuredHeight() - VANILLA_STEP_HEIGHT)
                * Math.min(1.0, observation.safetyScale());
        double previous = Math.max(
                VANILLA_STEP_HEIGHT,
                observation.currentTargetHeight()
        );
        double target = Math.min(
                scaledTarget,
                previous + observation.maximumIncreasePerTick()
        );
        target = Math.max(VANILLA_STEP_HEIGHT, target);
        if (!Double.isFinite(target)) {
            return Optional.empty();
        }
        return Optional.of(new StepPlan(target, target > VANILLA_STEP_HEIGHT));
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    record ControlState(
            boolean sessionReady,
            boolean alive,
            boolean spectator,
            boolean passenger,
            boolean inLiquid,
            boolean fallFlying,
            boolean onGround,
            boolean manualDirectional,
            boolean manualJump,
            boolean manualCrouch
    ) {
        boolean basicControlAllowed() {
            return sessionReady
                    && alive
                    && !spectator
                    && !passenger
                    && !inLiquid
                    && !fallFlying;
        }

        boolean manualOverride() {
            return manualDirectional || manualJump || manualCrouch;
        }
    }

    record EdgeObservation(
            ControlState control,
            double directionX,
            double directionZ,
            double lookAhead,
            boolean unsupportedAhead
    ) {
        EdgeObservation {
            control = Objects.requireNonNull(control, "control");
            if (!Double.isFinite(lookAhead) || lookAhead < 0.0) {
                throw new IllegalArgumentException(
                        "lookAhead must be finite and non-negative"
                );
            }
        }

        boolean directionFinite() {
            return Double.isFinite(directionX) && Double.isFinite(directionZ);
        }

        double directionLengthSquared() {
            return directionX * directionX + directionZ * directionZ;
        }
    }

    record SteeringObservation(
            ControlState control,
            double currentX,
            double currentZ,
            double targetX,
            double targetZ,
            double maximumSpeed,
            double tolerance,
            double safetyScale,
            boolean supported,
            boolean pathClear
    ) {
        SteeringObservation {
            control = Objects.requireNonNull(control, "control");
        }

        boolean finite() {
            return Double.isFinite(currentX)
                    && Double.isFinite(currentZ)
                    && Double.isFinite(targetX)
                    && Double.isFinite(targetZ)
                    && Double.isFinite(maximumSpeed)
                    && Double.isFinite(tolerance)
                    && Double.isFinite(safetyScale);
        }
    }

    record HorizontalPlan(
            double deltaX,
            double deltaZ,
            double remainingDistance,
            boolean stop
    ) {
        HorizontalPlan {
            if (!Double.isFinite(deltaX)
                    || !Double.isFinite(deltaZ)
                    || !finiteNonNegative(remainingDistance)) {
                throw new IllegalArgumentException(
                        "Horizontal plan must be finite"
                );
            }
        }

        static HorizontalPlan stop(double remainingDistance) {
            return new HorizontalPlan(0.0, 0.0, remainingDistance, true);
        }

        double horizontalSpeed() {
            return Math.hypot(deltaX, deltaZ);
        }
    }

    record HoleCandidate(
            long key,
            double centerX,
            double centerY,
            double centerZ,
            double distanceSquared,
            double verticalDistance,
            boolean safe,
            boolean loaded,
            boolean pathClear
    ) {
        HoleCandidate {
            if (!Double.isFinite(centerX)
                    || !Double.isFinite(centerY)
                    || !Double.isFinite(centerZ)
                    || !finiteNonNegative(distanceSquared)
                    || !finiteNonNegative(verticalDistance)) {
                throw new IllegalArgumentException("Invalid hole candidate");
            }
        }
    }

    record HoleSelection(
            Optional<HoleCandidate> candidate,
            int inspected
    ) {
        HoleSelection {
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (inspected < 0) {
                throw new IllegalArgumentException(
                        "inspected cannot be negative"
                );
            }
        }

        static HoleSelection none() {
            return new HoleSelection(Optional.empty(), 0);
        }
    }

    record StepObservation(
            ControlState control,
            double currentTargetHeight,
            double configuredHeight,
            double maximumIncreasePerTick,
            double safetyScale
    ) {
        StepObservation {
            control = Objects.requireNonNull(control, "control");
        }
    }

    record StepPlan(double targetHeight, boolean modifierRequired) {
        StepPlan {
            if (!Double.isFinite(targetHeight)
                    || targetHeight < VANILLA_STEP_HEIGHT) {
                throw new IllegalArgumentException(
                        "Step target cannot be below vanilla height"
                );
            }
        }
    }
}
