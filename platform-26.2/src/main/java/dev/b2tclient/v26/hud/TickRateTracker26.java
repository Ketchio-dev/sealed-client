package dev.b2tclient.v26.hud;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Estimates effective server TPS from authoritative game-time updates.
 *
 * <p>The networking thread publishes samples while the render thread reads
 * snapshots, so every state transition is synchronized. The estimator uses a
 * bounded window, median absolute deviation rejection, and a trimmed mean.
 * It never reports more than vanilla's 20 TPS.</p>
 */
public final class TickRateTracker26 {
    public static final int WINDOW_SIZE = 20;
    public static final double MAX_TPS = 20.0;
    public static final long STALE_AFTER_NANOS = 5_000_000_000L;

    private static final double MIN_TPS_SAMPLE = 0.05;
    private static final long MIN_SAMPLE_NANOS = 100_000_000L;
    private static final long MAX_SAMPLE_NANOS = 120_000_000_000L;
    private static final long MAX_TICK_DELTA = 2_400L;
    private static final double MIN_OUTLIER_BAND_TPS = 0.75;
    private static final double MAD_MULTIPLIER = 3.5;

    private final LongSupplier nanoTime;
    private final double[] samples = new double[WINDOW_SIZE];

    private boolean connected;
    private boolean hasAnchor;
    private long measurementStartedNanos;
    private long anchorGameTime;
    private long anchorNanos;
    private long lastAcceptedNanos;
    private int sampleStart;
    private int sampleCount;
    private double estimatedTps;

    public TickRateTracker26() {
        this(System::nanoTime);
    }

    TickRateTracker26(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public synchronized void connect() {
        connect(nanoTime.getAsLong());
    }

    public synchronized void connect(long nowNanos) {
        clear();
        connected = true;
        measurementStartedNanos = nowNanos;
        anchorNanos = nowNanos;
    }

    /**
     * Accepts any inbound packet, ignoring packets that do not carry server
     * game time.
     *
     * @return true when this packet produced a valid TPS sample
     */
    public synchronized boolean observeInbound(Packet<?> packet) {
        Objects.requireNonNull(packet, "packet");
        if (!(packet instanceof ClientboundSetTimePacket timePacket)) {
            return false;
        }
        return observeTimeUpdate(timePacket.gameTime(), nanoTime.getAsLong());
    }

    public synchronized boolean observeTimeUpdate(long gameTime, long nowNanos) {
        if (!connected) {
            return false;
        }
        if (!hasAnchor) {
            setAnchor(gameTime, nowNanos);
            return false;
        }

        long elapsedNanos = positiveDifference(nowNanos, anchorNanos);
        long elapsedTicks = gameTime - anchorGameTime;
        if (nowNanos < anchorNanos || elapsedTicks < 0L) {
            // A clock regression or a world-time reset starts a fresh
            // measurement epoch instead of contaminating the active window.
            clearSamples();
            measurementStartedNanos = nowNanos;
            setAnchor(gameTime, nowNanos);
            return false;
        }
        if (elapsedTicks == 0L) {
            // A repeated clock update is neither a zero-TPS sample nor a
            // reason to discard the established robust window.
            setAnchor(gameTime, nowNanos);
            return false;
        }

        setAnchor(gameTime, nowNanos);
        if (elapsedNanos < MIN_SAMPLE_NANOS
                || elapsedNanos > MAX_SAMPLE_NANOS
                || elapsedTicks > MAX_TICK_DELTA) {
            return false;
        }

        double rawTps = elapsedTicks * 1_000_000_000.0 / elapsedNanos;
        if (!Double.isFinite(rawTps) || rawTps < MIN_TPS_SAMPLE) {
            return false;
        }
        append(Math.min(MAX_TPS, rawTps));
        lastAcceptedNanos = nowNanos;
        return true;
    }

    public synchronized void disconnect() {
        clear();
    }

    public synchronized Snapshot snapshot() {
        return snapshot(nanoTime.getAsLong());
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        if (!connected) {
            return new Snapshot(Status.DISCONNECTED, 0.0, 0, -1L);
        }

        long freshnessAnchor = sampleCount == 0
                ? measurementStartedNanos
                : lastAcceptedNanos;
        long ageNanos = positiveDifference(nowNanos, freshnessAnchor);
        long ageMillis = ageNanos / 1_000_000L;
        if (ageNanos >= STALE_AFTER_NANOS) {
            return new Snapshot(
                    Status.STALE,
                    sampleCount == 0 ? 0.0 : estimatedTps,
                    sampleCount,
                    ageMillis
            );
        }
        if (sampleCount == 0) {
            return new Snapshot(Status.MEASURING, 0.0, 0, ageMillis);
        }
        return new Snapshot(
                Status.LIVE,
                estimatedTps,
                sampleCount,
                ageMillis
        );
    }

    private void append(double sample) {
        int index = (sampleStart + sampleCount) % WINDOW_SIZE;
        if (sampleCount == WINDOW_SIZE) {
            samples[sampleStart] = sample;
            sampleStart = (sampleStart + 1) % WINDOW_SIZE;
        } else {
            samples[index] = sample;
            sampleCount++;
        }
        estimatedTps = calculateRobustEstimate();
    }

    private double calculateRobustEstimate() {
        double[] ordered = orderedSamples();
        double median = median(ordered);
        if (ordered.length < 5) {
            return clamp(median);
        }

        double[] deviations = new double[ordered.length];
        for (int index = 0; index < ordered.length; index++) {
            deviations[index] = Math.abs(ordered[index] - median);
        }
        Arrays.sort(deviations);
        double band = Math.max(
                MIN_OUTLIER_BAND_TPS,
                median(deviations) * MAD_MULTIPLIER
        );
        double[] accepted = Arrays.stream(ordered)
                .filter(sample -> Math.abs(sample - median) <= band)
                .toArray();
        if (accepted.length == 0) {
            return clamp(median);
        }

        int trim = accepted.length >= 10
                ? Math.max(1, accepted.length / 10)
                : 0;
        int from = trim;
        int to = accepted.length - trim;
        if (from >= to) {
            return clamp(median);
        }
        double total = 0.0;
        for (int index = from; index < to; index++) {
            total += accepted[index];
        }
        return clamp(total / (to - from));
    }

    private double[] orderedSamples() {
        double[] ordered = new double[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            ordered[index] = samples[(sampleStart + index) % WINDOW_SIZE];
        }
        Arrays.sort(ordered);
        return ordered;
    }

    private static double median(double[] ordered) {
        int middle = ordered.length / 2;
        if ((ordered.length & 1) == 1) {
            return ordered[middle];
        }
        return (ordered[middle - 1] + ordered[middle]) / 2.0;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(MAX_TPS, value));
    }

    private static long positiveDifference(long later, long earlier) {
        if (later <= earlier) {
            return 0L;
        }
        long difference = later - earlier;
        return difference < 0L ? Long.MAX_VALUE : difference;
    }

    private void setAnchor(long gameTime, long nowNanos) {
        hasAnchor = true;
        anchorGameTime = gameTime;
        anchorNanos = nowNanos;
    }

    private void clearSamples() {
        Arrays.fill(samples, 0.0);
        sampleStart = 0;
        sampleCount = 0;
        estimatedTps = 0.0;
        lastAcceptedNanos = 0L;
    }

    private void clear() {
        connected = false;
        hasAnchor = false;
        measurementStartedNanos = 0L;
        anchorGameTime = 0L;
        anchorNanos = 0L;
        clearSamples();
    }

    public enum Status {
        DISCONNECTED,
        MEASURING,
        LIVE,
        STALE
    }

    public record Snapshot(
            Status status,
            double ticksPerSecond,
            int sampleCount,
            long sampleAgeMillis
    ) {
        public Snapshot {
            Objects.requireNonNull(status, "status");
            if (!Double.isFinite(ticksPerSecond)
                    || ticksPerSecond < 0.0
                    || ticksPerSecond > MAX_TPS
                    || sampleCount < 0
                    || sampleCount > WINDOW_SIZE
                    || sampleAgeMillis < -1L) {
                throw new IllegalArgumentException("Invalid tick-rate snapshot");
            }
        }

        public String displayText() {
            return switch (status) {
                case DISCONNECTED -> "Server TPS --";
                case MEASURING -> "Server TPS measuring...";
                case STALE -> "Server TPS -- (stale)";
                case LIVE -> String.format(
                        Locale.ROOT,
                        "Server TPS %.1f",
                        ticksPerSecond
                );
            };
        }
    }
}
