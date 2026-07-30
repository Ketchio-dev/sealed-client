package dev.sealedclient.v26.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogoutSpotsDecisionEngine26Test {
    private static final LogoutSpotsDecisionEngine26.SessionKey OVERWORLD =
            new LogoutSpotsDecisionEngine26.SessionKey(
                    7L,
                    "minecraft:overworld"
            );
    private static final UUID LOCAL =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALICE =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void requiresCompleteBaselineAndRepeatedEntityPlusTabAbsence() {
        LogoutSpotsDecisionEngine26 engine =
                new LogoutSpotsDecisionEngine26();
        LogoutSpotsDecisionEngine26.Config config = config(2);

        LogoutSpotsDecisionEngine26.TickResult baseline = engine.tick(
                OVERWORLD,
                0L,
                LOCAL,
                complete(player(ALICE, "Alice", 10.0, 64.0, -4.0)),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE
        );
        assertTrue(baseline.baselineEstablished());
        assertTrue(engine.snapshot(0L, config.lifetimeTicks()).isEmpty());

        engine.tick(
                OVERWORLD, 1L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE
        );
        assertTrue(engine.snapshot(1L, config.lifetimeTicks()).isEmpty());

        engine.tick(
                OVERWORLD, 2L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        assertTrue(engine.snapshot(2L, config.lifetimeTicks()).isEmpty());

        LogoutSpotsDecisionEngine26.TickResult confirmed = engine.tick(
                OVERWORLD, 3L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        assertEquals(1, confirmed.recordedSpots());
        LogoutSpotsDecisionEngine26.LogoutSpotSnapshot spot =
                engine.snapshot(3L, config.lifetimeTicks()).getFirst();
        assertEquals(ALICE, spot.playerId());
        assertEquals("Alice", spot.playerName());
        assertEquals(10.0, spot.x());
        assertEquals(64.0, spot.y());
        assertEquals(-4.0, spot.z());
        assertEquals("minecraft:overworld", spot.dimension());
    }

    @Test
    void unknownTabStateIncompleteFrameAndOversizedFrameFailClosed() {
        LogoutSpotsDecisionEngine26 engine =
                new LogoutSpotsDecisionEngine26();
        LogoutSpotsDecisionEngine26.Config config = config(1);
        engine.tick(
                OVERWORLD,
                0L,
                LOCAL,
                complete(player(ALICE, "Alice", 1.0, 2.0, 3.0)),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE
        );

        LogoutSpotsDecisionEngine26.TickResult incomplete = engine.tick(
                OVERWORLD,
                1L,
                LOCAL,
                LogoutSpotsDecisionEngine26.VisibleFrame.incomplete(List.of()),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        assertFalse(incomplete.frameAccepted());
        assertTrue(engine.snapshot(1L, config.lifetimeTicks()).isEmpty());

        engine.tick(
                OVERWORLD,
                2L,
                LOCAL,
                complete(),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.UNKNOWN
        );
        assertTrue(engine.snapshot(2L, config.lifetimeTicks()).isEmpty());

        List<LogoutSpotsDecisionEngine26.PlayerObservation> tooMany =
                new ArrayList<>();
        for (int index = 0; index < config.operationBudget() + 1; index++) {
            tooMany.add(player(
                    new UUID(1L, 100L + index),
                    "P" + index,
                    index,
                    64.0,
                    index
            ));
        }
        LogoutSpotsDecisionEngine26.TickResult oversized = engine.tick(
                OVERWORLD,
                3L,
                LOCAL,
                complete(tooMany),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        assertFalse(oversized.frameAccepted());
        assertTrue(oversized.operations() <= config.operationBudget());
        assertTrue(engine.snapshot(3L, config.lifetimeTicks()).isEmpty());
    }

    @Test
    void reappearanceDeduplicatesAndRemovesPriorSpot() {
        LogoutSpotsDecisionEngine26 engine =
                new LogoutSpotsDecisionEngine26();
        LogoutSpotsDecisionEngine26.Config config = config(1);
        LogoutSpotsDecisionEngine26.PlayerObservation alice =
                player(ALICE, "Alice", 5.0, 70.0, 8.0);

        engine.tick(
                OVERWORLD, 0L, LOCAL, complete(alice), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE
        );
        engine.tick(
                OVERWORLD, 1L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        assertEquals(1, engine.snapshot(1L, config.lifetimeTicks()).size());

        engine.tick(
                OVERWORLD, 2L, LOCAL, complete(alice, alice), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE
        );
        assertTrue(engine.snapshot(2L, config.lifetimeTicks()).isEmpty());

        engine.tick(
                OVERWORLD, 3L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        List<LogoutSpotsDecisionEngine26.LogoutSpotSnapshot> spots =
                engine.snapshot(3L, config.lifetimeTicks());
        assertEquals(1, spots.size());
        assertEquals(ALICE, spots.getFirst().playerId());
    }

    @Test
    void cachesExpireRemainBoundedAndSnapshotsAreImmutable() {
        LogoutSpotsDecisionEngine26 engine =
                new LogoutSpotsDecisionEngine26();
        LogoutSpotsDecisionEngine26.Config config =
                new LogoutSpotsDecisionEngine26.Config(
                        16, 2, 1, 5L, 1
                );
        UUID bob = new UUID(0L, 3L);
        UUID carol = new UUID(0L, 4L);

        engine.tick(
                OVERWORLD,
                0L,
                LOCAL,
                complete(
                        player(ALICE, "Alice", 0.0, 64.0, 0.0),
                        player(bob, "Bob", 16.0, 64.0, 0.0),
                        player(carol, "Carol", 32.0, 64.0, 0.0)
                ),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE
        );
        assertEquals(2, engine.status().trackedPlayers());
        assertEquals(2, engine.status().scanQueueSize());

        engine.tick(
                OVERWORLD, 1L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        List<LogoutSpotsDecisionEngine26.LogoutSpotSnapshot> snapshot =
                engine.snapshot(1L, config.lifetimeTicks());
        assertEquals(1, snapshot.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.add(snapshot.getFirst())
        );

        engine.tick(
                OVERWORLD, 7L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.UNKNOWN
        );
        assertTrue(engine.snapshot(7L, config.lifetimeTicks()).isEmpty());
    }

    @Test
    void dimensionOrTickRollbackClearsAllEvidence() {
        LogoutSpotsDecisionEngine26 engine =
                new LogoutSpotsDecisionEngine26();
        LogoutSpotsDecisionEngine26.Config config = config(1);
        engine.tick(
                OVERWORLD,
                10L,
                LOCAL,
                complete(player(ALICE, "Alice", 0.0, 64.0, 0.0)),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE
        );
        engine.tick(
                OVERWORLD, 11L, LOCAL, complete(), config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
        );
        assertEquals(1, engine.snapshot(11L, config.lifetimeTicks()).size());

        LogoutSpotsDecisionEngine26.SessionKey nether =
                new LogoutSpotsDecisionEngine26.SessionKey(
                        7L,
                        "minecraft:the_nether"
                );
        LogoutSpotsDecisionEngine26.TickResult reset = engine.tick(
                nether,
                12L,
                LOCAL,
                complete(),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.UNKNOWN
        );
        assertTrue(reset.sessionReset());
        assertTrue(engine.snapshot(12L, config.lifetimeTicks()).isEmpty());
        assertEquals(0, engine.status().trackedPlayers());

        LogoutSpotsDecisionEngine26.TickResult rollback = engine.tick(
                nether,
                1L,
                LOCAL,
                complete(),
                config,
                id -> LogoutSpotsDecisionEngine26.OnlineStatus.UNKNOWN
        );
        assertTrue(rollback.sessionReset());
    }

    @Test
    void validatesFinitePlayerDataAndImmutableFrame() {
        assertThrows(
                IllegalArgumentException.class,
                () -> player(ALICE, "Alice", Double.NaN, 0.0, 0.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LogoutSpotsDecisionEngine26.Config(
                        0, 1, 1, 1L, 1
                )
        );
        List<LogoutSpotsDecisionEngine26.PlayerObservation> mutable =
                new ArrayList<>();
        LogoutSpotsDecisionEngine26.VisibleFrame frame =
                LogoutSpotsDecisionEngine26.VisibleFrame.complete(mutable);
        mutable.add(player(ALICE, "Alice", 0.0, 0.0, 0.0));
        assertTrue(frame.players().isEmpty());
    }

    private static LogoutSpotsDecisionEngine26.Config config(
            int confirmations
    ) {
        return new LogoutSpotsDecisionEngine26.Config(
                8, 8, 4, 100L, confirmations
        );
    }

    private static LogoutSpotsDecisionEngine26.VisibleFrame complete(
            LogoutSpotsDecisionEngine26.PlayerObservation... players
    ) {
        return LogoutSpotsDecisionEngine26.VisibleFrame.complete(
                List.of(players)
        );
    }

    private static LogoutSpotsDecisionEngine26.VisibleFrame complete(
            List<LogoutSpotsDecisionEngine26.PlayerObservation> players
    ) {
        return LogoutSpotsDecisionEngine26.VisibleFrame.complete(players);
    }

    private static LogoutSpotsDecisionEngine26.PlayerObservation player(
            UUID id,
            String name,
            double x,
            double y,
            double z
    ) {
        return new LogoutSpotsDecisionEngine26.PlayerObservation(
                id,
                name,
                x,
                y,
                z,
                45.0F
        );
    }
}
