package dev.b2tclient.v26.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.b2tclient.v26.world.LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE;
import static dev.b2tclient.v26.world.LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE;
import static dev.b2tclient.v26.world.StashFinderDecisionEngine26.ContainerKind.BARREL;
import static dev.b2tclient.v26.world.StashFinderDecisionEngine26.ContainerKind.CHEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTrackerService26Test {
    private static final UUID LOCAL =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALICE =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void newChunkScanIntervalSkipsTicksWithoutLosingFirstSeenEvidence() {
        WorldTrackerService26 service = new WorldTrackerService26();
        service.setConfiguration(configuration(2, 8));
        FakeWorld world = new FakeWorld();
        world.load(0, 0);
        WorldTrackerService26.ModuleState modules =
                new WorldTrackerService26.ModuleState(true, false, false);

        service.tick(world, modules);
        assertNotNull(service.diagnostics().newChunks());
        assertFalse(service.diagnostics().newChunks().baselineEstablished());
        int lookupsAfterFirstScan = world.loadedLookups;

        service.tick(world, modules);
        assertNull(service.diagnostics().newChunks());
        assertEquals(lookupsAfterFirstScan, world.loadedLookups);

        service.tick(world, modules);
        assertTrue(service.diagnostics().newChunks().baselineEstablished());
        world.playerChunkX = 1;
        world.playerBlockX = 16;
        world.load(1, 0);

        service.tick(world, modules);
        assertNull(service.diagnostics().newChunks());
        assertTrue(service.renderSnapshot().newChunks().isEmpty());

        service.tick(world, modules);
        assertEquals(1, service.renderSnapshot().newChunks().size());
        NewChunksDecisionEngine26.ChunkSnapshot fresh =
                service.renderSnapshot().newChunks().getFirst();
        assertEquals(1, fresh.chunkX());
        assertEquals(
                NewChunksDecisionEngine26.Classification.FIRST_SEEN,
                fresh.classification()
        );
    }

    @Test
    void logoutSpotRequiresAcceptedFrameAndClearsOnReappearanceOrWorldChange() {
        WorldTrackerService26 service = new WorldTrackerService26();
        service.setConfiguration(configuration(1, 8));
        FakeWorld world = new FakeWorld();
        WorldTrackerService26.ModuleState modules =
                new WorldTrackerService26.ModuleState(false, true, false);
        world.visible.add(player(ALICE, "Alice", 10.0, 64.0, -4.0));
        world.online.put(ALICE, ONLINE);

        service.tick(world, modules);
        assertTrue(service.diagnostics().logoutSpots().baselineEstablished());

        world.visible.clear();
        world.online.put(ALICE, OFFLINE);
        service.tick(world, modules);
        assertEquals(1, service.renderSnapshot().logoutSpots().size());
        assertEquals(
                "Alice",
                service.renderSnapshot().logoutSpots().getFirst().playerName()
        );

        world.visible.add(player(ALICE, "Alice", 11.0, 65.0, -3.0));
        world.online.put(ALICE, ONLINE);
        service.tick(world, modules);
        assertTrue(service.renderSnapshot().logoutSpots().isEmpty());

        long priorEpoch = service.renderSnapshot().sessionEpoch();
        world.dimension = "minecraft:the_nether";
        world.levelIdentity = new Object();
        service.tick(world, modules);
        assertTrue(service.renderSnapshot().logoutSpots().isEmpty());
        assertTrue(service.renderSnapshot().sessionEpoch() > priorEpoch);
        assertTrue(service.diagnostics().sessionReset());
    }

    @Test
    void oversizedVisibleFrameFailsClosedBeforeIteratingPlayers() {
        WorldTrackerService26 service = new WorldTrackerService26();
        service.setConfiguration(configuration(1, 2));
        FakeWorld world = new FakeWorld();
        WorldTrackerService26.ModuleState modules =
                new WorldTrackerService26.ModuleState(false, true, false);
        world.visible.add(player(ALICE, "Alice", 0.0, 64.0, 0.0));
        world.visible.add(player(new UUID(0L, 3L), "Bob", 1.0, 64.0, 0.0));
        world.visible.add(player(new UUID(0L, 4L), "Carol", 2.0, 64.0, 0.0));

        service.tick(world, modules);

        LogoutSpotsDecisionEngine26.TickResult result =
                service.diagnostics().logoutSpots();
        assertFalse(result.frameAccepted());
        assertEquals(0, result.visiblePlayersProcessed());
        assertEquals(0, world.visibleIteratorRequests);
        assertTrue(service.renderSnapshot().logoutSpots().isEmpty());
    }

    @Test
    void stashAdapterOpensLoadedChunksOnlyAndPublishesImmutableSnapshot() {
        WorldTrackerService26 service = new WorldTrackerService26();
        service.setConfiguration(configuration(1, 8));
        FakeWorld world = new FakeWorld();
        world.put(
                0,
                0,
                block(0, 64, 0, CHEST),
                block(1, 64, 0, BARREL)
        );
        WorldTrackerService26.ModuleState modules =
                new WorldTrackerService26.ModuleState(false, false, true);

        for (int tick = 0;
                tick < 100 && service.renderSnapshot().stashes().isEmpty();
                tick++) {
            service.tick(world, modules);
            assertTrue(
                    service.diagnostics().stashFinder().operations()
                            <= service.configuration()
                            .stashFinder()
                            .operationBudget()
            );
        }

        assertEquals(1, service.renderSnapshot().stashes().size());
        assertEquals(
                2,
                service.renderSnapshot().stashes().getFirst().containerCount()
        );
        assertEquals(0, world.openedUnloadedChunks);
        assertTrue(world.openedLoadedChunks > 0);
        assertThrows(
                UnsupportedOperationException.class,
                () -> service.renderSnapshot().stashes().clear()
        );
    }

    @Test
    void unusableWorldReleaseAndConfigurationValidationClearPublishedState() {
        WorldTrackerService26 service = new WorldTrackerService26();
        FakeWorld world = new FakeWorld();
        service.tick(
                world,
                new WorldTrackerService26.ModuleState(true, false, false)
        );
        assertTrue(service.renderSnapshot().activeSession());

        world.usable = false;
        service.tick(
                world,
                new WorldTrackerService26.ModuleState(true, false, false)
        );
        assertFalse(service.renderSnapshot().activeSession());
        assertEquals(WorldTrackerService26.Diagnostics.EMPTY, service.diagnostics());

        WorldTrackerService26.Configuration valid = configuration(1, 8);
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldTrackerService26.Configuration(
                        valid.newChunks(),
                        valid.logoutSpots(),
                        valid.stashFinder(),
                        0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldTrackerService26.Configuration(
                        valid.newChunks(),
                        valid.logoutSpots(),
                        valid.stashFinder(),
                        41
                )
        );
    }

    private static WorldTrackerService26.Configuration configuration(
            int scanInterval,
            int logoutBudget
    ) {
        return new WorldTrackerService26.Configuration(
                new NewChunksDecisionEngine26.Config(
                        0,
                        1,
                        1_000L,
                        16,
                        1,
                        0L
                ),
                new LogoutSpotsDecisionEngine26.Config(
                        logoutBudget,
                        8,
                        4,
                        100L,
                        1
                ),
                new StashFinderDecisionEngine26.Config(
                        16,
                        8,
                        2,
                        8,
                        32,
                        1_000L
                ),
                scanInterval
        );
    }

    private static WorldTrackerService26.PlayerView player(
            UUID id,
            String name,
            double x,
            double y,
            double z
    ) {
        return new WorldTrackerService26.PlayerView(
                id,
                name,
                x,
                y,
                z,
                45.0F
        );
    }

    private static WorldTrackerService26.BlockEntityView block(
            int x,
            int y,
            int z,
            StashFinderDecisionEngine26.ContainerKind kind
    ) {
        return new WorldTrackerService26.BlockEntityView(x, y, z, kind);
    }

    private static long pack(int x, int z) {
        return ((long) x & 0xFFFF_FFFFL)
                | (((long) z & 0xFFFF_FFFFL) << 32);
    }

    private static final class FakeWorld
            implements WorldTrackerService26.WorldAccess {
        private boolean usable = true;
        private Object connectionIdentity = new Object();
        private Object levelIdentity = new Object();
        private Object playerIdentity = new Object();
        private String dimension = "minecraft:overworld";
        private int playerChunkX;
        private int playerChunkZ;
        private int playerBlockX;
        private int playerBlockY = 64;
        private int playerBlockZ;
        private final List<WorldTrackerService26.PlayerView> visible =
                new ArrayList<>();
        private final Map<UUID, LogoutSpotsDecisionEngine26.OnlineStatus>
                online = new HashMap<>();
        private final Set<Long> loaded = new HashSet<>();
        private final Map<Long, List<WorldTrackerService26.BlockEntityView>>
                entities = new HashMap<>();
        private int visibleIteratorRequests;
        private int loadedLookups;
        private int openedLoadedChunks;
        private int openedUnloadedChunks;

        private void load(int chunkX, int chunkZ) {
            loaded.add(pack(chunkX, chunkZ));
        }

        private void put(
                int chunkX,
                int chunkZ,
                WorldTrackerService26.BlockEntityView... blockEntities
        ) {
            long key = pack(chunkX, chunkZ);
            loaded.add(key);
            entities.put(key, List.of(blockEntities));
        }

        @Override
        public boolean usable() {
            return usable;
        }

        @Override
        public Object connectionIdentity() {
            return connectionIdentity;
        }

        @Override
        public Object levelIdentity() {
            return levelIdentity;
        }

        @Override
        public Object playerIdentity() {
            return playerIdentity;
        }

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public UUID localPlayerId() {
            return LOCAL;
        }

        @Override
        public int playerChunkX() {
            return playerChunkX;
        }

        @Override
        public int playerChunkZ() {
            return playerChunkZ;
        }

        @Override
        public int playerBlockX() {
            return playerBlockX;
        }

        @Override
        public int playerBlockY() {
            return playerBlockY;
        }

        @Override
        public int playerBlockZ() {
            return playerBlockZ;
        }

        @Override
        public int visiblePlayerCount() {
            return visible.size();
        }

        @Override
        public Iterable<WorldTrackerService26.PlayerView> visiblePlayers() {
            visibleIteratorRequests++;
            return List.copyOf(visible);
        }

        @Override
        public LogoutSpotsDecisionEngine26.OnlineStatus onlineStatus(
                UUID playerId
        ) {
            return online.getOrDefault(
                    playerId,
                    LogoutSpotsDecisionEngine26.OnlineStatus.UNKNOWN
            );
        }

        @Override
        public boolean isChunkLoaded(int chunkX, int chunkZ) {
            loadedLookups++;
            return loaded.contains(pack(chunkX, chunkZ));
        }

        @Override
        public Iterable<WorldTrackerService26.BlockEntityView>
                blockEntitiesInLoadedChunk(int chunkX, int chunkZ) {
            long key = pack(chunkX, chunkZ);
            if (!loaded.contains(key)) {
                openedUnloadedChunks++;
                throw new AssertionError("opened unloaded chunk");
            }
            openedLoadedChunks++;
            return entities.getOrDefault(key, List.of());
        }
    }
}
