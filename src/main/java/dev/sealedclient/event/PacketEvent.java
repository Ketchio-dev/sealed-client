package dev.sealedclient.event;

import net.minecraft.network.protocol.Packet;

import java.util.Objects;

public final class PacketEvent extends CancellableEvent {
    private final Packet<?> packet;
    private final Direction direction;
    private final long observedNanos;

    public PacketEvent(Packet<?> packet, Direction direction) {
        this.packet = Objects.requireNonNull(packet, "packet");
        this.direction = Objects.requireNonNull(direction, "direction");
        observedNanos = System.nanoTime();
    }

    public Packet<?> packet() {
        return packet;
    }

    public Direction direction() {
        return direction;
    }

    public long observedNanos() {
        return observedNanos;
    }

    public enum Direction {
        INBOUND,
        OUTBOUND
    }
}
