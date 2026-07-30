package dev.b2tclient.module.movement;

import dev.b2tclient.event.PacketEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe bridge between Netty packet observations and client-tick movement
 * decisions.
 *
 * <p>Packets are observed on the network thread while movement modules execute
 * on the client thread. The tracker only publishes monotonic counters and
 * timestamps so it never reads or mutates Minecraft world state off-thread.</p>
 */
public final class MovementNetworkTracker {
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final LongSupplier nanoTime;
    private final AtomicLong correctionSequence = new AtomicLong();
    private final AtomicLong lastInboundNanos = new AtomicLong();

    public MovementNetworkTracker() {
        this(System::nanoTime);
    }

    MovementNetworkTracker(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void observe(PacketEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.direction() != PacketEvent.Direction.INBOUND) {
            return;
        }

        lastInboundNanos.accumulateAndGet(event.observedNanos(), Math::max);
        if (event.packet() instanceof ClientboundPlayerPositionPacket) {
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

    public void reset() {
        lastInboundNanos.set(0L);
    }

    public record Snapshot(
            long correctionSequence,
            long inboundSilenceMillis
    ) {
    }
}
