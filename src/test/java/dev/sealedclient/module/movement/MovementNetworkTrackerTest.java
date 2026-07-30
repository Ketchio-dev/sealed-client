package dev.sealedclient.module.movement;

import dev.sealedclient.event.PacketEvent;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementNetworkTrackerTest {
    @Test
    void onlyInboundPositionPacketsAdvanceTheCorrectionSequence() {
        MovementNetworkTracker tracker = new MovementNetworkTracker();
        ClientboundPlayerPositionPacket correction =
                new ClientboundPlayerPositionPacket(
                        1,
                        new PositionMoveRotation(
                                Vec3.ZERO,
                                Vec3.ZERO,
                                0.0F,
                                0.0F
                        ),
                        Set.of()
                );

        tracker.observe(new PacketEvent(correction, PacketEvent.Direction.OUTBOUND));
        assertEquals(0L, tracker.snapshot().correctionSequence());
        assertEquals(-1L, tracker.snapshot().inboundSilenceMillis());

        tracker.observe(new PacketEvent(
                new ClientboundKeepAlivePacket(42L),
                PacketEvent.Direction.INBOUND
        ));
        MovementNetworkTracker.Snapshot traffic = tracker.snapshot();
        assertEquals(0L, traffic.correctionSequence());
        assertTrue(traffic.inboundSilenceMillis() >= 0L);

        tracker.observe(new PacketEvent(correction, PacketEvent.Direction.INBOUND));
        tracker.observe(new PacketEvent(correction, PacketEvent.Direction.INBOUND));
        assertEquals(2L, tracker.snapshot().correctionSequence());
    }

    @Test
    void disconnectResetDropsStaleTrafficAgeButKeepsMonotonicSequence() {
        MovementNetworkTracker tracker = new MovementNetworkTracker();
        ClientboundPlayerPositionPacket correction =
                new ClientboundPlayerPositionPacket(
                        7,
                        new PositionMoveRotation(
                                Vec3.ZERO,
                                Vec3.ZERO,
                                0.0F,
                                0.0F
                        ),
                        Set.of()
                );
        tracker.observe(new PacketEvent(correction, PacketEvent.Direction.INBOUND));

        tracker.reset();

        MovementNetworkTracker.Snapshot reset = tracker.snapshot();
        assertEquals(1L, reset.correctionSequence());
        assertEquals(-1L, reset.inboundSilenceMillis());
    }
}
