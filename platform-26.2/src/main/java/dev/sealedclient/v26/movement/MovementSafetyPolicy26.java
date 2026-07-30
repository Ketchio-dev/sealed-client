package dev.sealedclient.v26.movement;

import java.util.Objects;

/**
 * Pure, deterministic network-safety policy shared by 26.2 movement services.
 *
 * <p>The caller supplies one immutable observation per client tick. The policy
 * never reads Minecraft state, clocks, or packets itself. A new connection
 * identity requires a bounded warmup, elevated latency or stale inbound
 * traffic reduces the allowed movement scale, and severe latency, repeated
 * server corrections, or a large position discontinuity pause assistance.</p>
 *
 * <p>Explicit server correction sequence numbers are authoritative. Motion
 * reversal after {@link #recordApplied(double, double, double)} remains a
 * conservative fallback for proxy stacks that conceal or rewrite correction
 * packets.</p>
 */
public final class MovementSafetyPolicy26 {
    private static final double MINIMUM_APPLIED_SPEED_SQUARED = 0.0025;
    private static final double MINIMUM_OBSERVED_SPEED_SQUARED = 0.0004;
    private static final double REVERSE_DOT_THRESHOLD = -0.0015;

    private final Configuration configuration;
    private Object sessionIdentity;
    private boolean hasPosition;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean appliedLastTick;
    private double appliedX;
    private double appliedZ;
    private long lastCorrectionSequence;
    private int lastLatencyMillis = -1;
    private int warmupTicksRemaining;
    private int slowdownTicksRemaining;
    private int pauseTicksRemaining;
    private int correctionWindowTicksRemaining;
    private int correctionsInWindow;
    private int networkSlowdownRecoveryTicksRemaining;
    private int networkPauseRecoveryTicksRemaining;
    private Reason slowdownReason = Reason.SERVER_CORRECTION;
    private Reason pauseReason = Reason.REPEATED_SERVER_CORRECTION;
    private Decision decision = Decision.paused(Reason.UNUSABLE, 0, 0, 0);

    public MovementSafetyPolicy26() {
        this(Configuration.defaults());
    }

    public MovementSafetyPolicy26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    /**
     * Observes exactly one client tick and returns that tick's movement budget.
     */
    public Decision observe(Observation observation) {
        Observation current = Objects.requireNonNull(
                observation,
                "observation"
        );
        if (!current.usable()
                || current.sessionIdentity() == null
                || !finitePosition(current)) {
            clearSession();
            decision = Decision.paused(Reason.UNUSABLE, 0, 0, 0);
            return decision;
        }

        if (sessionIdentity != current.sessionIdentity()) {
            beginSession(current);
            if (configuration.reconnectWarmupTicks() > 0) {
                decision = Decision.paused(
                        Reason.RECONNECT_WARMUP,
                        warmupTicksRemaining,
                        0,
                        0
                );
                return decision;
            }
            return evaluateStableSession(current);
        }

        detectCorrection(current);
        updatePosition(current);
        appliedLastTick = false;
        advanceCorrectionWindow();

        if (warmupTicksRemaining > 0) {
            warmupTicksRemaining--;
            decision = Decision.paused(
                    Reason.RECONNECT_WARMUP,
                    warmupTicksRemaining,
                    slowdownTicksRemaining,
                    pauseTicksRemaining
            );
            return decision;
        }
        return evaluateStableSession(current);
    }

    /**
     * Records velocity actually applied after arbitration for correction
     * fallback detection on the next observation.
     */
    public void recordApplied(double x, double y, double z) {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || decision.state() == State.PAUSED) {
            appliedLastTick = false;
            return;
        }
        double horizontalSquared = x * x + z * z;
        if (horizontalSquared < MINIMUM_APPLIED_SPEED_SQUARED) {
            appliedLastTick = false;
            return;
        }
        appliedX = x;
        appliedZ = z;
        appliedLastTick = true;
    }

    /**
     * Clears all connection-local history and fails closed until a new
     * observation completes its warmup.
     */
    public void reset() {
        clearSession();
        decision = Decision.paused(Reason.UNUSABLE, 0, 0, 0);
    }

    public Decision decision() {
        return decision;
    }

    public State state() {
        return decision.state();
    }

    public Configuration configuration() {
        return configuration;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                sessionIdentity != null,
                decision,
                warmupTicksRemaining,
                slowdownTicksRemaining,
                pauseTicksRemaining,
                correctionWindowTicksRemaining,
                correctionsInWindow,
                lastCorrectionSequence,
                lastLatencyMillis,
                networkSlowdownRecoveryTicksRemaining,
                networkPauseRecoveryTicksRemaining
        );
    }

    private Decision evaluateStableSession(Observation observation) {
        NetworkAssessment network = assessNetwork(observation);
        if (network.state() == State.PAUSED) {
            networkPauseRecoveryTicksRemaining =
                    configuration.networkRecoveryTicks();
            networkSlowdownRecoveryTicksRemaining = 0;
            decision = Decision.paused(
                    network.reason(),
                    warmupTicksRemaining,
                    slowdownTicksRemaining,
                    pauseTicksRemaining
            );
            return decision;
        }

        if (pauseTicksRemaining > 0) {
            pauseTicksRemaining--;
            decision = Decision.paused(
                    pauseReason,
                    warmupTicksRemaining,
                    slowdownTicksRemaining,
                    pauseTicksRemaining
            );
            return decision;
        }

        if (networkPauseRecoveryTicksRemaining > 0) {
            networkPauseRecoveryTicksRemaining--;
            decision = Decision.paused(
                    Reason.NETWORK_RECOVERY,
                    warmupTicksRemaining,
                    slowdownTicksRemaining,
                    pauseTicksRemaining
            );
            return decision;
        }

        if (network.state() == State.SLOWDOWN) {
            networkSlowdownRecoveryTicksRemaining =
                    configuration.networkRecoveryTicks();
        }

        if (network.state() == State.SLOWDOWN
                || slowdownTicksRemaining > 0
                || networkSlowdownRecoveryTicksRemaining > 0) {
            Reason reason;
            if (network.state() == State.SLOWDOWN) {
                reason = network.reason();
            } else if (slowdownTicksRemaining > 0) {
                reason = slowdownReason;
            } else {
                reason = Reason.NETWORK_RECOVERY;
            }
            if (slowdownTicksRemaining > 0) {
                slowdownTicksRemaining--;
            }
            if (network.state() != State.SLOWDOWN
                    && networkSlowdownRecoveryTicksRemaining > 0) {
                networkSlowdownRecoveryTicksRemaining--;
            }
            decision = Decision.slowdown(
                    reason,
                    configuration.slowdownScale(),
                    warmupTicksRemaining,
                    slowdownTicksRemaining,
                    pauseTicksRemaining
            );
            return decision;
        }

        decision = Decision.active();
        return decision;
    }

    private void beginSession(Observation observation) {
        sessionIdentity = observation.sessionIdentity();
        hasPosition = true;
        lastX = observation.x();
        lastY = observation.y();
        lastZ = observation.z();
        appliedLastTick = false;
        appliedX = 0.0;
        appliedZ = 0.0;
        lastCorrectionSequence = observation.serverCorrectionSequence();
        lastLatencyMillis = observation.latencyMillis();
        warmupTicksRemaining = Math.max(
                0,
                configuration.reconnectWarmupTicks() - 1
        );
        slowdownTicksRemaining = 0;
        pauseTicksRemaining = 0;
        correctionWindowTicksRemaining = 0;
        correctionsInWindow = 0;
        networkSlowdownRecoveryTicksRemaining = 0;
        networkPauseRecoveryTicksRemaining = 0;
    }

    private void detectCorrection(Observation observation) {
        if (!hasPosition) {
            return;
        }
        double deltaX = observation.x() - lastX;
        double deltaY = observation.y() - lastY;
        double deltaZ = observation.z() - lastZ;
        double distanceSquared =
                deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        double teleportThreshold =
                configuration.teleportDistance()
                        * configuration.teleportDistance();

        int explicitCorrections = explicitCorrections(observation);
        if (distanceSquared > teleportThreshold) {
            pauseTicksRemaining = Math.max(
                    pauseTicksRemaining,
                    configuration.repeatedCorrectionPauseTicks()
            );
            pauseReason = Reason.POSITION_DISCONTINUITY;
            slowdownTicksRemaining = 0;
            correctionsInWindow = 0;
            correctionWindowTicksRemaining = 0;
            return;
        }

        if (explicitCorrections > 0) {
            recordCorrections(
                    explicitCorrections,
                    Reason.SERVER_CORRECTION
            );
            return;
        }

        double observedHorizontalSquared =
                deltaX * deltaX + deltaZ * deltaZ;
        if (!appliedLastTick
                || observedHorizontalSquared
                < MINIMUM_OBSERVED_SPEED_SQUARED) {
            return;
        }
        double dot = deltaX * appliedX + deltaZ * appliedZ;
        if (dot < REVERSE_DOT_THRESHOLD) {
            recordCorrections(1, Reason.MOTION_REVERSAL);
        }
    }

    private int explicitCorrections(Observation observation) {
        long sequence = observation.serverCorrectionSequence();
        long delta = sequence - lastCorrectionSequence;
        lastCorrectionSequence = sequence;
        if (delta <= 0L) {
            return 0;
        }
        return delta == 1L ? 1 : 2;
    }

    private void recordCorrections(int count, Reason firstReason) {
        for (int index = 0; index < count; index++) {
            if (correctionWindowTicksRemaining <= 0) {
                correctionsInWindow = 0;
            }
            correctionsInWindow++;
            correctionWindowTicksRemaining =
                    configuration.correctionWindowTicks();
            if (correctionsInWindow >= 2) {
                pauseTicksRemaining = Math.max(
                        pauseTicksRemaining,
                        configuration.repeatedCorrectionPauseTicks()
                );
                pauseReason = Reason.REPEATED_SERVER_CORRECTION;
                slowdownTicksRemaining = 0;
                correctionsInWindow = 0;
                correctionWindowTicksRemaining = 0;
                return;
            }
            slowdownTicksRemaining = Math.max(
                    slowdownTicksRemaining,
                    configuration.correctionSlowdownTicks()
            );
            slowdownReason = firstReason;
        }
    }

    private void advanceCorrectionWindow() {
        if (correctionWindowTicksRemaining > 0) {
            correctionWindowTicksRemaining--;
        } else {
            correctionsInWindow = 0;
        }
    }

    private NetworkAssessment assessNetwork(Observation observation) {
        int latency = observation.latencyMillis();
        boolean jitter = lastLatencyMillis >= 0
                && latency >= 0
                && Math.abs((long) latency - lastLatencyMillis)
                >= configuration.jitterBurstMillis();
        if (latency >= 0) {
            lastLatencyMillis = latency;
        }

        if (latency >= configuration.severeLatencyMillis()) {
            return new NetworkAssessment(
                    State.PAUSED,
                    Reason.SEVERE_LATENCY
            );
        }
        if (observation.inboundSilenceMillis()
                >= configuration.timedOutInboundMillis()) {
            return new NetworkAssessment(
                    State.PAUSED,
                    Reason.INBOUND_TIMEOUT
            );
        }
        if (latency >= configuration.highLatencyMillis()) {
            return new NetworkAssessment(
                    State.SLOWDOWN,
                    Reason.HIGH_LATENCY
            );
        }
        if (observation.inboundSilenceMillis()
                >= configuration.staleInboundMillis()) {
            return new NetworkAssessment(
                    State.SLOWDOWN,
                    Reason.STALE_INBOUND
            );
        }
        if (jitter) {
            return new NetworkAssessment(
                    State.SLOWDOWN,
                    Reason.LATENCY_JITTER
            );
        }
        return new NetworkAssessment(State.ACTIVE, Reason.STABLE);
    }

    private void updatePosition(Observation observation) {
        hasPosition = true;
        lastX = observation.x();
        lastY = observation.y();
        lastZ = observation.z();
    }

    private void clearSession() {
        sessionIdentity = null;
        hasPosition = false;
        lastX = 0.0;
        lastY = 0.0;
        lastZ = 0.0;
        appliedLastTick = false;
        appliedX = 0.0;
        appliedZ = 0.0;
        lastCorrectionSequence = 0L;
        lastLatencyMillis = -1;
        warmupTicksRemaining = 0;
        slowdownTicksRemaining = 0;
        pauseTicksRemaining = 0;
        correctionWindowTicksRemaining = 0;
        correctionsInWindow = 0;
        networkSlowdownRecoveryTicksRemaining = 0;
        networkPauseRecoveryTicksRemaining = 0;
    }

    private static boolean finitePosition(Observation observation) {
        return Double.isFinite(observation.x())
                && Double.isFinite(observation.y())
                && Double.isFinite(observation.z());
    }

    public enum State {
        ACTIVE,
        SLOWDOWN,
        PAUSED
    }

    public enum Reason {
        STABLE,
        UNUSABLE,
        RECONNECT_WARMUP,
        HIGH_LATENCY,
        SEVERE_LATENCY,
        LATENCY_JITTER,
        STALE_INBOUND,
        INBOUND_TIMEOUT,
        NETWORK_RECOVERY,
        SERVER_CORRECTION,
        REPEATED_SERVER_CORRECTION,
        MOTION_REVERSAL,
        POSITION_DISCONTINUITY
    }

    private record NetworkAssessment(State state, Reason reason) {
    }

    /**
     * Connection and network observation for one client tick.
     *
     * <p>{@code sessionIdentity} is compared by object identity, not
     * {@link Object#equals(Object)}. Pass the live connection or another stable
     * token and replace it on reconnect or dimension/session reset.</p>
     *
     * <p>A latency or inbound-silence value of {@code -1} means unavailable.
     * The correction sequence must be monotonic within one session.</p>
     */
    public record Observation(
            Object sessionIdentity,
            double x,
            double y,
            double z,
            int latencyMillis,
            boolean usable,
            long serverCorrectionSequence,
            long inboundSilenceMillis
    ) {
        public Observation {
            if (latencyMillis < -1) {
                throw new IllegalArgumentException(
                        "latencyMillis cannot be below -1"
                );
            }
            if (serverCorrectionSequence < 0L) {
                throw new IllegalArgumentException(
                        "serverCorrectionSequence cannot be negative"
                );
            }
            if (inboundSilenceMillis < -1L) {
                throw new IllegalArgumentException(
                        "inboundSilenceMillis cannot be below -1"
                );
            }
        }

        public Observation(
                Object sessionIdentity,
                double x,
                double y,
                double z,
                int latencyMillis,
                boolean usable
        ) {
            this(
                    sessionIdentity,
                    x,
                    y,
                    z,
                    latencyMillis,
                    usable,
                    0L,
                    -1L
            );
        }
    }

    public record Decision(
            State state,
            Reason reason,
            double scale,
            int warmupTicksRemaining,
            int slowdownTicksRemaining,
            int pauseTicksRemaining
    ) {
        public Decision {
            state = Objects.requireNonNull(state, "state");
            reason = Objects.requireNonNull(reason, "reason");
            if (!Double.isFinite(scale) || scale < 0.0 || scale > 1.0) {
                throw new IllegalArgumentException(
                        "scale must be finite and between 0 and 1"
                );
            }
            if (warmupTicksRemaining < 0
                    || slowdownTicksRemaining < 0
                    || pauseTicksRemaining < 0) {
                throw new IllegalArgumentException(
                        "remaining tick counts cannot be negative"
                );
            }
        }

        public boolean canApply() {
            return state != State.PAUSED;
        }

        public boolean networkReady() {
            return canApply();
        }

        private static Decision active() {
            return new Decision(
                    State.ACTIVE,
                    Reason.STABLE,
                    1.0,
                    0,
                    0,
                    0
            );
        }

        private static Decision slowdown(
                Reason reason,
                double scale,
                int warmupTicks,
                int slowdownTicks,
                int pauseTicks
        ) {
            return new Decision(
                    State.SLOWDOWN,
                    reason,
                    scale,
                    warmupTicks,
                    slowdownTicks,
                    pauseTicks
            );
        }

        private static Decision paused(
                Reason reason,
                int warmupTicks,
                int slowdownTicks,
                int pauseTicks
        ) {
            return new Decision(
                    State.PAUSED,
                    reason,
                    0.0,
                    warmupTicks,
                    slowdownTicks,
                    pauseTicks
            );
        }
    }

    public record Snapshot(
            boolean sessionPresent,
            Decision decision,
            int warmupTicksRemaining,
            int slowdownTicksRemaining,
            int pauseTicksRemaining,
            int correctionWindowTicksRemaining,
            int correctionsInWindow,
            long lastCorrectionSequence,
            int lastLatencyMillis,
            int networkSlowdownRecoveryTicksRemaining,
            int networkPauseRecoveryTicksRemaining
    ) {
        public Snapshot {
            decision = Objects.requireNonNull(decision, "decision");
            if (warmupTicksRemaining < 0
                    || slowdownTicksRemaining < 0
                    || pauseTicksRemaining < 0
                    || correctionWindowTicksRemaining < 0
                    || correctionsInWindow < 0
                    || lastCorrectionSequence < 0L
                    || lastLatencyMillis < -1
                    || networkSlowdownRecoveryTicksRemaining < 0
                    || networkPauseRecoveryTicksRemaining < 0) {
                throw new IllegalArgumentException(
                        "snapshot counters cannot be negative"
                );
            }
        }
    }

    public record Configuration(
            int highLatencyMillis,
            int severeLatencyMillis,
            int jitterBurstMillis,
            long staleInboundMillis,
            long timedOutInboundMillis,
            int reconnectWarmupTicks,
            int correctionSlowdownTicks,
            int repeatedCorrectionPauseTicks,
            int correctionWindowTicks,
            int networkRecoveryTicks,
            double slowdownScale,
            double teleportDistance
    ) {
        private static final int MAXIMUM_TICKS = 20 * 60 * 10;

        public Configuration {
            if (highLatencyMillis < 0
                    || severeLatencyMillis <= highLatencyMillis) {
                throw new IllegalArgumentException(
                        "severe latency must exceed non-negative high latency"
                );
            }
            if (jitterBurstMillis < 0) {
                throw new IllegalArgumentException(
                        "jitterBurstMillis cannot be negative"
                );
            }
            if (staleInboundMillis < 0L
                    || timedOutInboundMillis <= staleInboundMillis) {
                throw new IllegalArgumentException(
                        "inbound timeout must exceed non-negative stale time"
                );
            }
            requireTicks(reconnectWarmupTicks, "reconnectWarmupTicks");
            requireTicks(correctionSlowdownTicks, "correctionSlowdownTicks");
            requireTicks(
                    repeatedCorrectionPauseTicks,
                    "repeatedCorrectionPauseTicks"
            );
            requireTicks(correctionWindowTicks, "correctionWindowTicks");
            requireTicks(networkRecoveryTicks, "networkRecoveryTicks");
            if (!Double.isFinite(slowdownScale)
                    || slowdownScale <= 0.0
                    || slowdownScale > 1.0) {
                throw new IllegalArgumentException(
                        "slowdownScale must be finite and in (0, 1]"
                );
            }
            if (!Double.isFinite(teleportDistance)
                    || teleportDistance <= 0.0
                    || teleportDistance > 1_024.0) {
                throw new IllegalArgumentException(
                        "teleportDistance must be finite and in (0, 1024]"
                );
            }
        }

        public static Configuration defaults() {
            return new Configuration(
                    350,
                    700,
                    180,
                    1_500L,
                    5_000L,
                    2,
                    30,
                    40,
                    80,
                    5,
                    0.45,
                    6.0
            );
        }

        private static void requireTicks(int ticks, String name) {
            if (ticks < 0 || ticks > MAXIMUM_TICKS) {
                throw new IllegalArgumentException(
                        name + " must be between 0 and " + MAXIMUM_TICKS
                );
            }
        }
    }
}
