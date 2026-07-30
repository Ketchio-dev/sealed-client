package dev.b2tclient.v26.hud;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HudMetricsBridge26Test {
    @Test
    void sessionChecksAndTrackerMutationShareOneMonitor() throws Exception {
        assertSynchronized(
                "observeInbound",
                Connection.class,
                Packet.class
        );
        assertSynchronized(
                "observeEntityEvent",
                Connection.class,
                Entity.class,
                byte.class
        );
        assertSynchronized("bind", Connection.class, LocalPlayer.class);
        assertSynchronized("disconnect", Connection.class);
        assertSynchronized("reset");
    }

    private static void assertSynchronized(
            String methodName,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = HudMetricsBridge26.class.getDeclaredMethod(
                methodName,
                parameterTypes
        );
        assertTrue(
                Modifier.isSynchronized(method.getModifiers()),
                () -> method + " must make binding validation and metric "
                        + "mutation atomic"
        );
    }
}
