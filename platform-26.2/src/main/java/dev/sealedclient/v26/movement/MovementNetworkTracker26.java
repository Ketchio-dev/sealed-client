package dev.sealedclient.v26.movement;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe, read-only network observations for movement safety decisions.
 *
 * <p>The Netty thread publishes only monotonic counters and timestamps. The
 * client tick never reads packet-owned world state, and the network thread
 * never touches Minecraft entities.</p>
 */
public final class MovementNetworkTracker26 {
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final LongSupplier nanoTime;
    private final AtomicLong correctionSequence = new AtomicLong();
    private final AtomicLong lastInboundNanos = new AtomicLong();

    public MovementNetworkTracker26() {
        this(System::nanoTime);
    }

    MovementNetworkTracker26(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void observeInbound(Packet<?> packet) {
        Objects.requireNonNull(packet, "packet");
        long observed = Math.max(1L, nanoTime.getAsLong());
        lastInboundNanos.accumulateAndGet(observed, Math::max);
        if (packet instanceof ClientboundPlayerPositionPacket) {
            correctionSequence.incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        long lastInbound = lastInboundNanos.get();
        long silenceMillis = -1L;
        if (lastInbound > 0L) {
            long elapsed = Math.max(0L, nanoTime.getAsLong() - lastInbound);
            silenceMillis = elapsed / NANOS_PER_MILLISECOND;
        }
        return new Snapshot(correctionSequence.get(), silenceMillis);
    }

    /**
     * Clears connection-local timing without rewinding the monotonic
     * correction counter. Consumers detect the new player/world context
     * separately and therefore cannot mistake an old correction for a new one.
     */
    public void resetConnection() {
        lastInboundNanos.set(0L);
    }

    public record Snapshot(
            long correctionSequence,
            long inboundSilenceMillis
    ) {
        public Snapshot {
            if (correctionSequence < 0L) {
                throw new IllegalArgumentException(
                        "correctionSequence cannot be negative"
                );
            }
            if (inboundSilenceMillis < -1L) {
                throw new IllegalArgumentException(
                        "inboundSilenceMillis cannot be below -1"
                );
            }
        }
    }
}
