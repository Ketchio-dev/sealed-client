package dev.sealedclient.v26.movement;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovementNetworkTracker26Test {
    @Test
    void recordsInboundSilenceAndPositionCorrectionsMonotonically() {
        AtomicLong nanos = new AtomicLong(1_000_000L);
        MovementNetworkTracker26 tracker =
                new MovementNetworkTracker26(nanos::get);

        tracker.observeInbound(positionPacket(1));
        assertEquals(1L, tracker.snapshot().correctionSequence());
        assertEquals(0L, tracker.snapshot().inboundSilenceMillis());

        nanos.addAndGet(275_000_000L);
        assertEquals(275L, tracker.snapshot().inboundSilenceMillis());

        tracker.observeInbound(positionPacket(2));
        assertEquals(2L, tracker.snapshot().correctionSequence());
        assertEquals(0L, tracker.snapshot().inboundSilenceMillis());
    }

    @Test
    void ordinaryPacketsUpdateTimingWithoutCreatingCorrections() {
        AtomicLong nanos = new AtomicLong(5_000_000L);
        MovementNetworkTracker26 tracker =
                new MovementNetworkTracker26(nanos::get);

        tracker.observeInbound(
                new ClientboundPlayerRotationPacket(10.0F, false, 5.0F, false)
        );

        assertEquals(0L, tracker.snapshot().correctionSequence());
        assertEquals(0L, tracker.snapshot().inboundSilenceMillis());
    }

    @Test
    void resetClearsOnlyConnectionTiming() {
        AtomicLong nanos = new AtomicLong(1_000_000L);
        MovementNetworkTracker26 tracker =
                new MovementNetworkTracker26(nanos::get);
        tracker.observeInbound(positionPacket(1));

        tracker.resetConnection();

        assertEquals(1L, tracker.snapshot().correctionSequence());
        assertEquals(-1L, tracker.snapshot().inboundSilenceMillis());
    }

    @Test
    void rejectsInvalidInputsAndSnapshots() {
        MovementNetworkTracker26 tracker =
                new MovementNetworkTracker26(() -> 1L);
        assertThrows(
                NullPointerException.class,
                () -> tracker.observeInbound(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementNetworkTracker26.Snapshot(-1L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MovementNetworkTracker26.Snapshot(0L, -2L)
        );
    }

    private static ClientboundPlayerPositionPacket positionPacket(int id) {
        return ClientboundPlayerPositionPacket.of(
                id,
                new PositionMoveRotation(
                        Vec3.ZERO,
                        Vec3.ZERO,
                        0.0F,
                        0.0F
                ),
                Set.of()
        );
    }
}
