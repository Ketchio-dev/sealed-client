package dev.sealedclient.v26.hud;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.item.Items;

import java.util.Objects;

/**
 * Thread-safe handoff between network/player mixins and HUD rendering.
 *
 * <p>The bridge binds local metrics to the exact play connection and local-player
 * object. Packets from status, transfer, auxiliary, or stale connections are
 * ignored.</p>
 */
public final class HudMetricsBridge26 {
    private static final TickRateTracker26 TICK_RATE =
            new TickRateTracker26();
    private static final LocalTotemPopTracker26 LOCAL_TOTEM_POPS =
            new LocalTotemPopTracker26();

    private static volatile Binding binding;

    private HudMetricsBridge26() {
    }

    public static synchronized void bind(
            Connection connection,
            LocalPlayer localPlayer
    ) {
        bind(connection, localPlayer, System.nanoTime());
    }

    public static synchronized void bind(
            Connection connection,
            LocalPlayer localPlayer,
            long nowNanos
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(localPlayer, "localPlayer");
        Binding previous = binding;
        boolean newConnection = previous == null
                || previous.connection() != connection;
        boolean newPlayer = newConnection
                || previous.localPlayer() != localPlayer;

        if (newConnection) {
            TICK_RATE.connect(nowNanos);
        }
        if (newPlayer) {
            LOCAL_TOTEM_POPS.connect(
                    localPlayer.getUUID(),
                    localPlayer.getId(),
                    nowNanos
            );
        }
        if (newPlayer) {
            binding = new Binding(connection, localPlayer);
        }
        observeLocalState(localPlayer, nowNanos);
    }

    public static synchronized void observeInbound(
            Connection connection,
            Packet<?> packet
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(packet, "packet");
        Binding current = binding;
        if (current != null && current.connection() == connection) {
            TICK_RATE.observeInbound(packet);
        }
    }

    public static void observeLocalPlayerTick(
            Connection connection,
            LocalPlayer localPlayer
    ) {
        bind(connection, localPlayer, System.nanoTime());
    }

    public static synchronized LocalTotemPopTracker26.EventResult
            observeEntityEvent(
            Connection connection,
            Entity entity,
            byte eventId
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(entity, "entity");
        Binding current = binding;
        if (current == null
                || current.connection() != connection
                || current.localPlayer() != entity) {
            return LocalTotemPopTracker26.EventResult.WRONG_PLAYER;
        }
        if (eventId != EntityEvent.PROTECTED_FROM_DEATH) {
            return LocalTotemPopTracker26.EventResult.IGNORED_EVENT;
        }

        LocalPlayer player = current.localPlayer();
        return LOCAL_TOTEM_POPS.observeProtectedFromDeath(
                player.getUUID(),
                player.getId(),
                player.getHealth(),
                offhandTotems(player),
                player.isAlive() && !player.isDeadOrDying()
        );
    }

    public static synchronized void disconnect(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        Binding current = binding;
        if (current == null || current.connection() != connection) {
            return;
        }
        binding = null;
        TICK_RATE.disconnect();
        LOCAL_TOTEM_POPS.disconnect();
    }

    public static synchronized void reset() {
        binding = null;
        TICK_RATE.disconnect();
        LOCAL_TOTEM_POPS.disconnect();
    }

    public static TickRateTracker26.Snapshot tickRateSnapshot() {
        return TICK_RATE.snapshot();
    }

    public static LocalTotemPopTracker26.Snapshot localTotemPopSnapshot() {
        return LOCAL_TOTEM_POPS.snapshot();
    }

    private static void observeLocalState(
            LocalPlayer player,
            long nowNanos
    ) {
        LOCAL_TOTEM_POPS.observeState(
                player.getUUID(),
                player.getId(),
                player.getHealth(),
                offhandTotems(player),
                player.isAlive() && !player.isDeadOrDying(),
                nowNanos
        );
    }

    private static int offhandTotems(LocalPlayer player) {
        return player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                ? player.getOffhandItem().getCount()
                : 0;
    }

    private record Binding(
            Connection connection,
            LocalPlayer localPlayer
    ) {
    }
}
