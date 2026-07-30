package dev.sealedclient.v26.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Live Minecraft 26.2 adapter for New Chunks, Logout Spots, and Stash Finder.
 *
 * <p>The adapter owns no event registration and changes no central state. Its
 * owner calls {@link #tick(Minecraft, ModuleState)} once from
 * {@code END_CLIENT_TICK}, and {@link #release()} on disconnect/shutdown.
 * Minecraft objects are converted into bounded, immutable observations for
 * the three pure decision engines.</p>
 */
public final class WorldTrackerService26 {
    private final NewChunksDecisionEngine26 newChunks =
            new NewChunksDecisionEngine26();
    private final LogoutSpotsDecisionEngine26 logoutSpots =
            new LogoutSpotsDecisionEngine26();
    private final StashFinderDecisionEngine26 stashFinder =
            new StashFinderDecisionEngine26();

    private volatile Configuration configuration = Configuration.defaults();
    private volatile RenderSnapshot renderSnapshot = RenderSnapshot.EMPTY;
    private Object connectionIdentity;
    private Object levelIdentity;
    private Object playerIdentity;
    private String activeDimension;
    private long sessionEpoch;
    private long logicalTick;
    private int publishedLogoutSpotCount;
    private int publishedStashCount;
    private ModuleState lastModuleState = ModuleState.DISABLED;
    private Configuration lastAppliedConfiguration;
    private Diagnostics diagnostics = Diagnostics.EMPTY;

    public Configuration configuration() {
        return configuration;
    }

    /**
     * Atomically replaces all scan settings for the next client tick.
     */
    public void setConfiguration(Configuration nextConfiguration) {
        configuration = Objects.requireNonNull(
                nextConfiguration,
                "nextConfiguration"
        );
    }

    /**
     * Adapts a live Minecraft client into the bounded world-access contract.
     */
    public void tick(Minecraft client, ModuleState modules) {
        Objects.requireNonNull(modules, "modules");
        if (!usable(client)) {
            release();
            return;
        }
        tick(new MinecraftWorldAccess(client), modules);
    }

    /**
     * Testable adapter boundary. Runtime callers should use the Minecraft
     * overload above.
     */
    void tick(WorldAccess world, ModuleState modules) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(modules, "modules");
        if (!world.usable()) {
            release();
            return;
        }

        String dimension = requireText(world.dimension(), "dimension");
        boolean contextChanged =
                connectionIdentity != world.connectionIdentity()
                        || levelIdentity != world.levelIdentity()
                        || playerIdentity != world.playerIdentity()
                        || !Objects.equals(activeDimension, dimension);
        if (contextChanged) {
            sessionEpoch = saturatingIncrement(sessionEpoch);
            logicalTick = 0L;
            connectionIdentity = world.connectionIdentity();
            levelIdentity = world.levelIdentity();
            playerIdentity = world.playerIdentity();
            activeDimension = dimension;
        } else {
            logicalTick = saturatingIncrement(logicalTick);
        }

        Configuration settings = configuration;
        boolean configurationChanged =
                !settings.equals(lastAppliedConfiguration);
        long tick = logicalTick;
        NewChunksDecisionEngine26.SessionKey chunkSession =
                new NewChunksDecisionEngine26.SessionKey(
                        sessionEpoch,
                        dimension
                );
        LogoutSpotsDecisionEngine26.SessionKey logoutSession =
                new LogoutSpotsDecisionEngine26.SessionKey(
                        sessionEpoch,
                        dimension
                );
        StashFinderDecisionEngine26.SessionKey stashSession =
                new StashFinderDecisionEngine26.SessionKey(
                        sessionEpoch,
                        dimension
                );

        NewChunksDecisionEngine26.TickResult newChunksResult = null;
        boolean scanNewChunks = modules.newChunks()
                && (contextChanged
                || configurationChanged
                || !lastModuleState.newChunks()
                || tick % settings.newChunksScanIntervalTicks() == 0L);
        if (scanNewChunks) {
            newChunksResult = newChunks.tick(
                    chunkSession,
                    tick,
                    world.playerChunkX(),
                    world.playerChunkZ(),
                    settings.newChunks(),
                    world::isChunkLoaded
            );
        } else if (!modules.newChunks()) {
            newChunks.clear();
        }

        LogoutSpotsDecisionEngine26.TickResult logoutResult = null;
        if (modules.logoutSpots()) {
            LogoutSpotsDecisionEngine26.VisibleFrame frame =
                    visibleFrame(world, settings.logoutSpots());
            logoutResult = logoutSpots.tick(
                    logoutSession,
                    tick,
                    world.localPlayerId(),
                    frame,
                    settings.logoutSpots(),
                    world::onlineStatus
            );
        } else {
            logoutSpots.clear();
        }

        StashFinderDecisionEngine26.TickResult stashResult = null;
        if (modules.stashFinder()) {
            stashResult = stashFinder.tick(
                    stashSession,
                    tick,
                    world.playerBlockX(),
                    world.playerBlockY(),
                    world.playerBlockZ(),
                    settings.stashFinder(),
                    new StashFinderDecisionEngine26.LoadedChunkSource() {
                        @Override
                        public boolean isLoaded(int chunkX, int chunkZ) {
                            return world.isChunkLoaded(chunkX, chunkZ);
                        }

                        @Override
                        public Iterable<StashFinderDecisionEngine26.BlockObservation>
                                blockEntitiesInLoadedChunk(
                                        int chunkX,
                                        int chunkZ
                                ) {
                            return mapBlockEntities(
                                    world.blockEntitiesInLoadedChunk(
                                            chunkX,
                                            chunkZ
                                    )
                            );
                        }
                    }
            );
        } else {
            stashFinder.clear();
        }

        diagnostics = new Diagnostics(
                sessionEpoch,
                logicalTick,
                contextChanged,
                modules,
                newChunksResult,
                logoutResult,
                stashResult,
                stashFinder.scanProgress()
        );
        publishIfChanged(
                modules,
                settings,
                configurationChanged,
                newChunksResult,
                logoutResult,
                stashResult
        );
        lastModuleState = modules;
        lastAppliedConfiguration = settings;
    }

    /**
     * Immutable cross-phase snapshot consumed by render extraction.
     */
    public RenderSnapshot renderSnapshot() {
        return renderSnapshot;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Clears every session-local observation and published render record.
     */
    public void release() {
        newChunks.clear();
        logoutSpots.clear();
        stashFinder.clear();
        renderSnapshot = RenderSnapshot.EMPTY;
        connectionIdentity = null;
        levelIdentity = null;
        playerIdentity = null;
        activeDimension = null;
        logicalTick = 0L;
        publishedLogoutSpotCount = 0;
        publishedStashCount = 0;
        lastModuleState = ModuleState.DISABLED;
        lastAppliedConfiguration = null;
        diagnostics = Diagnostics.EMPTY;
    }

    private void publishIfChanged(
            ModuleState modules,
            Configuration settings,
            boolean configurationChanged,
            NewChunksDecisionEngine26.TickResult newChunksResult,
            LogoutSpotsDecisionEngine26.TickResult logoutResult,
            StashFinderDecisionEngine26.TickResult stashResult
    ) {
        boolean moduleStateChanged = !modules.equals(lastModuleState);
        int logoutCount = modules.logoutSpots()
                ? logoutSpots.status().retainedSpots()
                : 0;
        int stashCount = modules.stashFinder()
                ? stashResult == null
                ? publishedStashCount
                : stashResult.publishedStashes()
                : 0;

        boolean newChunksDirty = moduleStateChanged
                || configurationChanged
                || newChunksResult != null
                && (newChunksResult.newlyClassified() > 0
                || newChunksResult.expiredEntries() > 0
                || newChunksResult.sessionReset());
        boolean logoutDirty = moduleStateChanged
                || configurationChanged
                || logoutCount != publishedLogoutSpotCount
                || logoutResult != null
                && (logoutResult.recordedSpots() > 0
                || logoutResult.expiredEntries() > 0
                || logoutResult.sessionReset());
        boolean stashDirty = moduleStateChanged
                || configurationChanged
                || stashCount != publishedStashCount
                || stashResult != null
                && (stashResult.publishedSweeps() > 0
                || stashResult.expiredEntries() > 0
                || stashResult.evictedEntries() > 0
                || stashResult.sessionReset());

        if (!newChunksDirty && !logoutDirty && !stashDirty) {
            return;
        }

        List<NewChunksDecisionEngine26.ChunkSnapshot> chunks =
                modules.newChunks()
                        ? newChunks.freshlyObservedSnapshot()
                        : List.of();
        List<LogoutSpotsDecisionEngine26.LogoutSpotSnapshot> logouts =
                modules.logoutSpots()
                        ? logoutSpots.snapshot(
                                logicalTick,
                                settings.logoutSpots().lifetimeTicks()
                        )
                        : List.of();
        List<StashFinderDecisionEngine26.StashSnapshot> stashes =
                modules.stashFinder()
                        ? stashFinder.snapshot()
                        : List.of();
        renderSnapshot = new RenderSnapshot(
                true,
                sessionEpoch,
                logicalTick,
                activeDimension,
                modules,
                chunks,
                logouts,
                stashes
        );
        publishedLogoutSpotCount = logouts.size();
        publishedStashCount = stashes.size();
    }

    private static LogoutSpotsDecisionEngine26.VisibleFrame visibleFrame(
            WorldAccess world,
            LogoutSpotsDecisionEngine26.Config config
    ) {
        int count = world.visiblePlayerCount();
        if (count < 0 || count > config.operationBudget()) {
            return LogoutSpotsDecisionEngine26.VisibleFrame.incomplete(
                    List.of()
            );
        }
        List<LogoutSpotsDecisionEngine26.PlayerObservation> result =
                new ArrayList<>(count);
        int consumed = 0;
        try {
            for (PlayerView player : world.visiblePlayers()) {
                if (consumed++ >= count
                        || result.size() >= config.operationBudget()) {
                    return LogoutSpotsDecisionEngine26.VisibleFrame.incomplete(
                            List.of()
                    );
                }
                result.add(new LogoutSpotsDecisionEngine26.PlayerObservation(
                        player.playerId(),
                        player.playerName(),
                        player.x(),
                        player.y(),
                        player.z(),
                        player.yaw()
                ));
            }
        } catch (RuntimeException ignored) {
            return LogoutSpotsDecisionEngine26.VisibleFrame.incomplete(
                    List.of()
            );
        }
        if (consumed != count) {
            return LogoutSpotsDecisionEngine26.VisibleFrame.incomplete(
                    List.of()
            );
        }
        return LogoutSpotsDecisionEngine26.VisibleFrame.complete(result);
    }

    private static Iterable<StashFinderDecisionEngine26.BlockObservation>
            mapBlockEntities(Iterable<BlockEntityView> source) {
        Objects.requireNonNull(source, "source");
        return () -> new Iterator<>() {
            private final Iterator<BlockEntityView> delegate =
                    source.iterator();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public StashFinderDecisionEngine26.BlockObservation next() {
                BlockEntityView blockEntity = delegate.next();
                return new StashFinderDecisionEngine26.BlockObservation(
                        blockEntity.blockX(),
                        blockEntity.blockY(),
                        blockEntity.blockZ(),
                        blockEntity.kind()
                );
            }
        };
    }

    private static boolean usable(Minecraft client) {
        return client != null
                && client.level != null
                && client.player != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
    }

    private static StashFinderDecisionEngine26.ContainerKind classify(
            BlockEntityType<?> type
    ) {
        if (type == BlockEntityTypes.CHEST) {
            return StashFinderDecisionEngine26.ContainerKind.CHEST;
        }
        if (type == BlockEntityTypes.TRAPPED_CHEST) {
            return StashFinderDecisionEngine26.ContainerKind.TRAPPED_CHEST;
        }
        if (type == BlockEntityTypes.ENDER_CHEST) {
            return StashFinderDecisionEngine26.ContainerKind.ENDER_CHEST;
        }
        if (type == BlockEntityTypes.BARREL) {
            return StashFinderDecisionEngine26.ContainerKind.BARREL;
        }
        if (type == BlockEntityTypes.SHULKER_BOX) {
            return StashFinderDecisionEngine26.ContainerKind.SHULKER_BOX;
        }
        if (type == BlockEntityTypes.HOPPER) {
            return StashFinderDecisionEngine26.ContainerKind.HOPPER;
        }
        if (type == BlockEntityTypes.DISPENSER) {
            return StashFinderDecisionEngine26.ContainerKind.DISPENSER;
        }
        if (type == BlockEntityTypes.DROPPER) {
            return StashFinderDecisionEngine26.ContainerKind.DROPPER;
        }
        if (type == BlockEntityTypes.FURNACE) {
            return StashFinderDecisionEngine26.ContainerKind.FURNACE;
        }
        if (type == BlockEntityTypes.BLAST_FURNACE) {
            return StashFinderDecisionEngine26.ContainerKind.BLAST_FURNACE;
        }
        if (type == BlockEntityTypes.SMOKER) {
            return StashFinderDecisionEngine26.ContainerKind.SMOKER;
        }
        if (type == BlockEntityTypes.BREWING_STAND) {
            return StashFinderDecisionEngine26.ContainerKind.BREWING_STAND;
        }
        if (type == BlockEntityTypes.CRAFTER) {
            return StashFinderDecisionEngine26.ContainerKind.CRAFTER;
        }
        return StashFinderDecisionEngine26.ContainerKind.IGNORED;
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    interface WorldAccess {
        boolean usable();

        Object connectionIdentity();

        Object levelIdentity();

        Object playerIdentity();

        String dimension();

        UUID localPlayerId();

        int playerChunkX();

        int playerChunkZ();

        int playerBlockX();

        int playerBlockY();

        int playerBlockZ();

        int visiblePlayerCount();

        Iterable<PlayerView> visiblePlayers();

        LogoutSpotsDecisionEngine26.OnlineStatus onlineStatus(UUID playerId);

        boolean isChunkLoaded(int chunkX, int chunkZ);

        Iterable<BlockEntityView> blockEntitiesInLoadedChunk(
                int chunkX,
                int chunkZ
        );
    }

    record PlayerView(
            UUID playerId,
            String playerName,
            double x,
            double y,
            double z,
            float yaw
    ) {
        PlayerView {
            Objects.requireNonNull(playerId, "playerId");
            playerName = requireText(playerName, "playerName");
        }
    }

    record BlockEntityView(
            int blockX,
            int blockY,
            int blockZ,
            StashFinderDecisionEngine26.ContainerKind kind
    ) {
        BlockEntityView {
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record Configuration(
            NewChunksDecisionEngine26.Config newChunks,
            LogoutSpotsDecisionEngine26.Config logoutSpots,
            StashFinderDecisionEngine26.Config stashFinder,
            int newChunksScanIntervalTicks
    ) {
        public Configuration {
            Objects.requireNonNull(newChunks, "newChunks");
            Objects.requireNonNull(logoutSpots, "logoutSpots");
            Objects.requireNonNull(stashFinder, "stashFinder");
            if (newChunksScanIntervalTicks < 1
                    || newChunksScanIntervalTicks > 40) {
                throw new IllegalArgumentException(
                        "newChunksScanIntervalTicks must be between 1 and 40"
                );
            }
        }

        public static Configuration defaults() {
            return new Configuration(
                    NewChunksDecisionEngine26.Config.defaults(),
                    LogoutSpotsDecisionEngine26.Config.defaults(),
                    StashFinderDecisionEngine26.Config.defaults(),
                    5
            );
        }
    }

    public record ModuleState(
            boolean newChunks,
            boolean logoutSpots,
            boolean stashFinder
    ) {
        public static final ModuleState DISABLED =
                new ModuleState(false, false, false);
    }

    public record RenderSnapshot(
            boolean activeSession,
            long sessionEpoch,
            long logicalTick,
            String dimension,
            ModuleState modules,
            List<NewChunksDecisionEngine26.ChunkSnapshot> newChunks,
            List<LogoutSpotsDecisionEngine26.LogoutSpotSnapshot> logoutSpots,
            List<StashFinderDecisionEngine26.StashSnapshot> stashes
    ) {
        public static final RenderSnapshot EMPTY = new RenderSnapshot(
                false,
                0L,
                0L,
                "",
                ModuleState.DISABLED,
                List.of(),
                List.of(),
                List.of()
        );

        public RenderSnapshot {
            if (sessionEpoch < 0L || logicalTick < 0L) {
                throw new IllegalArgumentException(
                        "snapshot counters cannot be negative"
                );
            }
            if (activeSession) {
                dimension = requireText(dimension, "dimension");
            } else {
                dimension = Objects.requireNonNull(dimension, "dimension");
            }
            Objects.requireNonNull(modules, "modules");
            newChunks = List.copyOf(
                    Objects.requireNonNull(newChunks, "newChunks")
            );
            logoutSpots = List.copyOf(
                    Objects.requireNonNull(logoutSpots, "logoutSpots")
            );
            stashes = List.copyOf(
                    Objects.requireNonNull(stashes, "stashes")
            );
        }
    }

    public record Diagnostics(
            long sessionEpoch,
            long logicalTick,
            boolean sessionReset,
            ModuleState modules,
            NewChunksDecisionEngine26.TickResult newChunks,
            LogoutSpotsDecisionEngine26.TickResult logoutSpots,
            StashFinderDecisionEngine26.TickResult stashFinder,
            StashFinderDecisionEngine26.ScanProgress stashProgress
    ) {
        public static final Diagnostics EMPTY = new Diagnostics(
                0L,
                0L,
                false,
                ModuleState.DISABLED,
                null,
                null,
                null,
                null
        );

        public Diagnostics {
            Objects.requireNonNull(modules, "modules");
        }
    }

    private static final class MinecraftWorldAccess implements WorldAccess {
        private final Minecraft client;
        private final ClientPacketListener connection;
        private final ClientLevel level;
        private final LocalPlayer player;

        private MinecraftWorldAccess(Minecraft client) {
            this.client = client;
            connection = client.getConnection();
            level = client.level;
            player = client.player;
        }

        @Override
        public boolean usable() {
            return WorldTrackerService26.usable(client)
                    && client.getConnection() == connection
                    && client.level == level
                    && client.player == player;
        }

        @Override
        public Object connectionIdentity() {
            return connection.getConnection();
        }

        @Override
        public Object levelIdentity() {
            return level;
        }

        @Override
        public Object playerIdentity() {
            return player;
        }

        @Override
        public String dimension() {
            return level.dimension().identifier().toString();
        }

        @Override
        public UUID localPlayerId() {
            return player.getUUID();
        }

        @Override
        public int playerChunkX() {
            return player.chunkPosition().x();
        }

        @Override
        public int playerChunkZ() {
            return player.chunkPosition().z();
        }

        @Override
        public int playerBlockX() {
            return player.blockPosition().getX();
        }

        @Override
        public int playerBlockY() {
            return player.blockPosition().getY();
        }

        @Override
        public int playerBlockZ() {
            return player.blockPosition().getZ();
        }

        @Override
        public int visiblePlayerCount() {
            return level.players().size();
        }

        @Override
        public Iterable<PlayerView> visiblePlayers() {
            return () -> new Iterator<>() {
                private final Iterator<AbstractClientPlayer> delegate =
                        level.players().iterator();

                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public PlayerView next() {
                    AbstractClientPlayer next = delegate.next();
                    return new PlayerView(
                            next.getUUID(),
                            next.getGameProfile().name(),
                            next.getX(),
                            next.getY(),
                            next.getZ(),
                            next.getYRot()
                    );
                }
            };
        }

        @Override
        public LogoutSpotsDecisionEngine26.OnlineStatus onlineStatus(
                UUID playerId
        ) {
            if (client.getConnection() != connection
                    || !connection.getConnection().isConnected()) {
                return LogoutSpotsDecisionEngine26.OnlineStatus.UNKNOWN;
            }
            return connection.getPlayerInfo(playerId) == null
                    ? LogoutSpotsDecisionEngine26.OnlineStatus.OFFLINE
                    : LogoutSpotsDecisionEngine26.OnlineStatus.ONLINE;
        }

        @Override
        public boolean isChunkLoaded(int chunkX, int chunkZ) {
            return level.hasChunk(chunkX, chunkZ);
        }

        @Override
        public Iterable<BlockEntityView> blockEntitiesInLoadedChunk(
                int chunkX,
                int chunkZ
        ) {
            if (!level.hasChunk(chunkX, chunkZ)) {
                return List.of();
            }
            LevelChunk chunk = level.getChunk(chunkX, chunkZ);
            return () -> new Iterator<>() {
                private final Iterator<BlockEntity> delegate =
                        chunk.getBlockEntities().values().iterator();

                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public BlockEntityView next() {
                    BlockEntity blockEntity = delegate.next();
                    BlockPos position = blockEntity.getBlockPos();
                    return new BlockEntityView(
                            position.getX(),
                            position.getY(),
                            position.getZ(),
                            classify(blockEntity.getType())
                    );
                }
            };
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(name + " must be 1..256 chars");
        }
        return normalized;
    }
}
