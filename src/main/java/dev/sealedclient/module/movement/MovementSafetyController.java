package dev.sealedclient.module.movement;

import java.util.Objects;

/**
 * Deterministic fail-closed policy shared by movement assists.
 *
 * <p>The controller does not inspect or mutate Minecraft state. Callers feed it
 * one observation per client tick and record a velocity only when they actually
 * applied one. Explicit position-correction packets are authoritative. A
 * reversal of the velocity remains a fallback for unusual proxy stacks that
 * hide or rewrite those packets. The first correction slows assistance down
 * and a repeated correction temporarily pauses it.</p>
 */
final class MovementSafetyController {
    static final int HIGH_LATENCY_MS = 350;
    static final int SEVERE_LATENCY_MS = 700;
    static final int JITTER_BURST_MS = 180;
    static final long STALE_INBOUND_MS = 1_500L;
    static final long TIMED_OUT_INBOUND_MS = 5_000L;
    static final int CONTEXT_WARMUP_TICKS = 2;
    static final int SLOWDOWN_TICKS = 30;
    static final int PAUSE_TICKS = 40;
    static final int CORRECTION_WINDOW_TICKS = 80;
    static final int NETWORK_RECOVERY_TICKS = 5;
    static final double SLOWDOWN_SCALE = 0.45;

    private static final double TELEPORT_DISTANCE_SQUARED = 36.0;
    private static final double MIN_APPLIED_SPEED_SQUARED = 0.0025;
    private static final double MIN_OBSERVED_SPEED_SQUARED = 0.0004;
    private static final double REVERSE_DOT_THRESHOLD = -0.0015;

    private Object context;
    private boolean hasPosition;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean appliedLastTick;
    private double appliedX;
    private double appliedZ;
    private int warmupTicks;
    private int slowdownTicks;
    private int pauseTicks;
    private int correctionWindowTicks;
    private int correctionsInWindow;
    private long lastServerCorrectionSequence;
    private int lastLatencyMs = -1;
    private int networkSlowdownRecoveryTicks;
    private int networkPauseRecoveryTicks;
    private State state = State.ACTIVE;

    Decision observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!observation.usable() || observation.context() == null) {
            reset();
            state = State.PAUSED;
            return decision();
        }

        if (!Objects.equals(context, observation.context())) {
            beginContext(observation);
            state = State.PAUSED;
            return decision();
        }

        detectCorrection(observation);
        updatePosition(observation);
        appliedLastTick = false;

        if (correctionWindowTicks > 0) {
            correctionWindowTicks--;
        } else {
            correctionsInWindow = 0;
        }

        if (warmupTicks > 0) {
            warmupTicks--;
            state = State.PAUSED;
            return decision();
        }
        NetworkState networkState = observeNetworkState(observation);
        if (networkState == NetworkState.PAUSED) {
            networkPauseRecoveryTicks = NETWORK_RECOVERY_TICKS;
            networkSlowdownRecoveryTicks = 0;
            state = State.PAUSED;
            return decision();
        }
        if (pauseTicks > 0) {
            pauseTicks--;
            state = State.PAUSED;
            return decision();
        }
        if (networkPauseRecoveryTicks > 0) {
            networkPauseRecoveryTicks--;
            state = State.PAUSED;
            return decision();
        }
        if (networkState == NetworkState.SLOWDOWN) {
            networkSlowdownRecoveryTicks = NETWORK_RECOVERY_TICKS;
        }
        if (observation.latencyMs() >= HIGH_LATENCY_MS
                || slowdownTicks > 0
                || networkSlowdownRecoveryTicks > 0) {
            if (slowdownTicks > 0) {
                slowdownTicks--;
            }
            if (networkState != NetworkState.SLOWDOWN
                    && networkSlowdownRecoveryTicks > 0) {
                networkSlowdownRecoveryTicks--;
            }
            state = State.SLOWDOWN;
            return decision();
        }

        state = State.ACTIVE;
        return decision();
    }

    void recordApplied(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            appliedLastTick = false;
            return;
        }
        double horizontalSquared = x * x + z * z;
        if (horizontalSquared < MIN_APPLIED_SPEED_SQUARED) {
            appliedLastTick = false;
            return;
        }
        appliedX = x;
        appliedZ = z;
        appliedLastTick = true;
    }

    void reset() {
        context = null;
        hasPosition = false;
        appliedLastTick = false;
        appliedX = 0.0;
        appliedZ = 0.0;
        warmupTicks = 0;
        slowdownTicks = 0;
        pauseTicks = 0;
        correctionWindowTicks = 0;
        correctionsInWindow = 0;
        lastServerCorrectionSequence = 0L;
        lastLatencyMs = -1;
        networkSlowdownRecoveryTicks = 0;
        networkPauseRecoveryTicks = 0;
        state = State.ACTIVE;
    }

    State state() {
        return state;
    }

    private void beginContext(Observation observation) {
        context = observation.context();
        hasPosition = true;
        lastX = observation.x();
        lastY = observation.y();
        lastZ = observation.z();
        appliedLastTick = false;
        warmupTicks = Math.max(0, CONTEXT_WARMUP_TICKS - 1);
        slowdownTicks = 0;
        pauseTicks = 0;
        correctionWindowTicks = 0;
        correctionsInWindow = 0;
        lastServerCorrectionSequence = observation.serverCorrectionSequence();
        lastLatencyMs = observation.latencyMs();
        networkSlowdownRecoveryTicks = 0;
        networkPauseRecoveryTicks = 0;
    }

    private void detectCorrection(Observation observation) {
        if (!hasPosition) {
            return;
        }
        double deltaX = observation.x() - lastX;
        double deltaY = observation.y() - lastY;
        double deltaZ = observation.z() - lastZ;
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        int explicitCorrections = explicitCorrections(observation);
        if (distanceSquared > TELEPORT_DISTANCE_SQUARED) {
            pauseTicks = Math.max(pauseTicks, PAUSE_TICKS);
            slowdownTicks = 0;
            correctionsInWindow = 0;
            correctionWindowTicks = 0;
            return;
        }

        if (explicitCorrections > 0) {
            recordCorrections(explicitCorrections);
            return;
        }

        double observedHorizontalSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (!appliedLastTick || observedHorizontalSquared < MIN_OBSERVED_SPEED_SQUARED) {
            return;
        }
        double dot = deltaX * appliedX + deltaZ * appliedZ;
        if (dot >= REVERSE_DOT_THRESHOLD) {
            return;
        }

        recordCorrections(1);
    }

    private int explicitCorrections(Observation observation) {
        long sequence = observation.serverCorrectionSequence();
        long delta = sequence - lastServerCorrectionSequence;
        lastServerCorrectionSequence = sequence;
        if (delta <= 0L) {
            return 0;
        }
        return delta == 1L ? 1 : 2;
    }

    private void recordCorrections(int count) {
        for (int index = 0; index < count; index++) {
            if (correctionWindowTicks <= 0) {
                correctionsInWindow = 0;
            }
            correctionsInWindow++;
            correctionWindowTicks = CORRECTION_WINDOW_TICKS;
            if (correctionsInWindow >= 2) {
                pauseTicks = Math.max(pauseTicks, PAUSE_TICKS);
                slowdownTicks = 0;
                correctionsInWindow = 0;
                correctionWindowTicks = 0;
                return;
            }
            slowdownTicks = Math.max(slowdownTicks, SLOWDOWN_TICKS);
        }
    }

    private NetworkState observeNetworkState(Observation observation) {
        int latencyMs = observation.latencyMs();
        boolean jitterBurst = lastLatencyMs >= 0
                && latencyMs >= 0
                && Math.abs(latencyMs - lastLatencyMs) >= JITTER_BURST_MS;
        if (latencyMs >= 0) {
            lastLatencyMs = latencyMs;
        }

        if (latencyMs >= SEVERE_LATENCY_MS
                || observation.inboundSilenceMillis() >= TIMED_OUT_INBOUND_MS) {
            return NetworkState.PAUSED;
        }
        if (latencyMs >= HIGH_LATENCY_MS
                || observation.inboundSilenceMillis() >= STALE_INBOUND_MS
                || jitterBurst) {
            return NetworkState.SLOWDOWN;
        }
        return NetworkState.STABLE;
    }

    private void updatePosition(Observation observation) {
        hasPosition = true;
        lastX = observation.x();
        lastY = observation.y();
        lastZ = observation.z();
    }

    private Decision decision() {
        return switch (state) {
            case ACTIVE -> new Decision(state, 1.0);
            case SLOWDOWN -> new Decision(state, SLOWDOWN_SCALE);
            case PAUSED -> new Decision(state, 0.0);
        };
    }

    enum State {
        ACTIVE,
        SLOWDOWN,
        PAUSED
    }

    private enum NetworkState {
        STABLE,
        SLOWDOWN,
        PAUSED
    }

    record Observation(
            Object context,
            double x,
            double y,
            double z,
            int latencyMs,
            boolean usable,
            long serverCorrectionSequence,
            long inboundSilenceMillis
    ) {
        Observation {
            latencyMs = Math.max(-1, latencyMs);
            inboundSilenceMillis = Math.max(-1L, inboundSilenceMillis);
        }

        Observation(
                Object context,
                double x,
                double y,
                double z,
                int latencyMs,
                boolean usable
        ) {
            this(context, x, y, z, latencyMs, usable, 0L, 0L);
        }
    }

    record Decision(State state, double scale) {
        boolean canApply() {
            return state != State.PAUSED;
        }
    }
}
