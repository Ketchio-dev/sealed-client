package dev.b2tclient.v26.world;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure, fail-closed logout detector for Minecraft 26.2.
 *
 * <p>A spot is emitted only after a player is absent from a complete visible
 * entity frame and the online-player lookup independently reports that UUID
 * absent for the configured number of checks. Unknown tab-list state, an
 * incomplete visible frame, or a frame larger than the remaining tick budget
 * can only delay a detection; none can create a logout spot.</p>
 *
 * <p>Visible observations, expiry removals, and disappearance checks each
 * consume one unit of a fixed operation budget. The tracked-player and spot
 * caches are independently bounded.</p>
 */
public final class LogoutSpotsDecisionEngine26 {
    private final LinkedHashMap<UUID, TrackedPlayer> trackedPlayers =
            new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<UUID, StoredSpot> spots =
            new LinkedHashMap<>();
    private final LinkedHashSet<UUID> scanQueue = new LinkedHashSet<>();

    private SessionKey session;
    private long lastTick = -1L;
    private long frameSequence;
    private boolean baselineEstablished;

    /**
     * Processes one complete-or-incomplete visible-player frame.
     */
    public TickResult tick(
            SessionKey requestedSession,
            long tick,
            UUID localPlayerId,
            VisibleFrame frame,
            Config config,
            OnlineStatusLookup onlinePlayers
    ) {
        Objects.requireNonNull(requestedSession, "requestedSession");
        Objects.requireNonNull(localPlayerId, "localPlayerId");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        if (tick < 0L) {
            throw new IllegalArgumentException("tick cannot be negative");
        }

        boolean reset = !requestedSession.equals(session)
                || (lastTick >= 0L && tick < lastTick);
        if (reset) {
            reset(requestedSession, tick);
        }
        lastTick = tick;

        int operations = 0;
        int expired = 0;
        int evictedTracked = 0;
        int recorded = 0;
        int checkedDisappearances = 0;

        while (operations < config.operationBudget()
                && pruneOneExpired(tick, config.lifetimeTicks())) {
            operations++;
            expired++;
        }

        while (operations < config.operationBudget()
                && trackedPlayers.size() > config.maximumTrackedPlayers()) {
            evictOldestTracked();
            operations++;
            evictedTracked++;
        }
        while (operations < config.operationBudget()
                && spots.size() > config.maximumEntries()) {
            evictOldestSpot();
            operations++;
        }

        int remaining = config.operationBudget() - operations;
        boolean frameAccepted = frame.complete()
                && frame.players().size() <= remaining;
        if (!frameAccepted) {
            return new TickResult(
                    operations,
                    0,
                    0,
                    expired,
                    evictedTracked,
                    false,
                    baselineEstablished,
                    trackedPlayers.size(),
                    spots.size(),
                    reset
            );
        }

        frameSequence = saturatingIncrement(frameSequence);
        for (PlayerObservation observation : frame.players()) {
            operations++;
            if (observation.playerId().equals(localPlayerId)) {
                continue;
            }
            TrackedPlayer tracked = trackedPlayers.get(observation.playerId());
            if (tracked == null) {
                tracked = new TrackedPlayer(
                        observation.playerId(),
                        observation.playerName(),
                        observation.x(),
                        observation.y(),
                        observation.z(),
                        observation.yaw(),
                        frameSequence,
                        0L,
                        0,
                        false
                );
                trackedPlayers.put(observation.playerId(), tracked);
                scanQueue.add(tracked.playerId);
            } else {
                tracked.updateVisible(observation, frameSequence);
            }
            spots.remove(observation.playerId());
            tracked.loggedOut = false;
            enforceTrackedMaximum(config.maximumTrackedPlayers());
        }

        if (!baselineEstablished) {
            baselineEstablished = true;
            return new TickResult(
                    operations,
                    frame.players().size(),
                    0,
                    expired,
                    evictedTracked,
                    true,
                    true,
                    trackedPlayers.size(),
                    spots.size(),
                    reset
            );
        }

        while (operations < config.operationBudget() && !scanQueue.isEmpty()) {
            Iterator<UUID> queueIterator = scanQueue.iterator();
            UUID playerId = queueIterator.next();
            queueIterator.remove();
            TrackedPlayer tracked = trackedPlayers.get(playerId);
            operations++;
            if (tracked == null) {
                continue;
            }
            scanQueue.add(playerId);
            checkedDisappearances++;

            if (tracked.lastVisibleFrame == frameSequence
                    || tracked.lastCheckedFrame == frameSequence
                    || tracked.loggedOut) {
                continue;
            }
            tracked.lastCheckedFrame = frameSequence;

            OnlineStatus status;
            try {
                status = Objects.requireNonNullElse(
                        onlinePlayers.status(tracked.playerId),
                        OnlineStatus.UNKNOWN
                );
            } catch (RuntimeException ignored) {
                status = OnlineStatus.UNKNOWN;
            }
            if (status != OnlineStatus.OFFLINE) {
                tracked.offlineConfirmations = 0;
                continue;
            }

            tracked.offlineConfirmations++;
            if (tracked.offlineConfirmations
                    < config.offlineConfirmationChecks()) {
                continue;
            }

            StoredSpot spot = new StoredSpot(
                    tracked.playerId,
                    tracked.playerName,
                    tracked.x,
                    tracked.y,
                    tracked.z,
                    tracked.yaw,
                    requestedSession.dimension(),
                    tick
            );
            spots.remove(tracked.playerId);
            spots.put(tracked.playerId, spot);
            tracked.loggedOut = true;
            recorded++;
            enforceSpotMaximum(config.maximumEntries());
        }

        return new TickResult(
                operations,
                frame.players().size(),
                checkedDisappearances,
                expired,
                evictedTracked,
                frameAccepted,
                baselineEstablished,
                trackedPlayers.size(),
                spots.size(),
                reset,
                recorded
        );
    }

    /**
     * Returns immutable detached records in observation order.
     */
    public List<LogoutSpotSnapshot> snapshot(long tick, long lifetimeTicks) {
        if (tick < 0L || lifetimeTicks < 1L) {
            throw new IllegalArgumentException(
                    "tick and lifetimeTicks must be positive"
            );
        }
        List<LogoutSpotSnapshot> result = new ArrayList<>(spots.size());
        for (StoredSpot spot : spots.values()) {
            long age = Math.max(0L, tick - spot.observedAtTick);
            long remaining = Math.max(0L, lifetimeTicks - age);
            result.add(new LogoutSpotSnapshot(
                    spot.playerId,
                    spot.playerName,
                    spot.x,
                    spot.y,
                    spot.z,
                    spot.yaw,
                    spot.dimension,
                    spot.observedAtTick,
                    age,
                    remaining
            ));
        }
        return List.copyOf(result);
    }

    public Status status() {
        return new Status(
                session,
                lastTick,
                frameSequence,
                baselineEstablished,
                trackedPlayers.size(),
                spots.size(),
                scanQueue.size()
        );
    }

    public void clear() {
        session = null;
        lastTick = -1L;
        frameSequence = 0L;
        baselineEstablished = false;
        trackedPlayers.clear();
        spots.clear();
        scanQueue.clear();
    }

    private void reset(SessionKey requestedSession, long tick) {
        clear();
        session = requestedSession;
        lastTick = tick;
    }

    private boolean pruneOneExpired(long tick, long lifetimeTicks) {
        Iterator<Map.Entry<UUID, StoredSpot>> iterator =
                spots.entrySet().iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        StoredSpot oldest = iterator.next().getValue();
        if (tick - oldest.observedAtTick <= lifetimeTicks) {
            return false;
        }
        iterator.remove();
        return true;
    }

    private void enforceTrackedMaximum(int maximum) {
        while (trackedPlayers.size() > maximum) {
            evictOldestTracked();
        }
    }

    private void evictOldestTracked() {
        Iterator<Map.Entry<UUID, TrackedPlayer>> iterator =
                trackedPlayers.entrySet().iterator();
        if (!iterator.hasNext()) {
            return;
        }
        UUID playerId = iterator.next().getKey();
        iterator.remove();
        scanQueue.remove(playerId);
    }

    private void enforceSpotMaximum(int maximum) {
        while (spots.size() > maximum) {
            evictOldestSpot();
        }
    }

    private void evictOldestSpot() {
        Iterator<UUID> iterator = spots.keySet().iterator();
        if (!iterator.hasNext()) {
            return;
        }
        iterator.next();
        iterator.remove();
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    @FunctionalInterface
    public interface OnlineStatusLookup {
        OnlineStatus status(UUID playerId);
    }

    public enum OnlineStatus {
        ONLINE,
        OFFLINE,
        UNKNOWN
    }

    public record SessionKey(long connectionEpoch, String dimension) {
        public SessionKey {
            if (connectionEpoch < 0L) {
                throw new IllegalArgumentException(
                        "connectionEpoch cannot be negative"
                );
            }
            dimension = requireText(dimension, "dimension");
        }
    }

    public record PlayerObservation(
            UUID playerId,
            String playerName,
            double x,
            double y,
            double z,
            float yaw
    ) {
        public PlayerObservation {
            Objects.requireNonNull(playerId, "playerId");
            playerName = requireText(playerName, "playerName");
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || !Float.isFinite(yaw)) {
                throw new IllegalArgumentException(
                        "player coordinates and yaw must be finite"
                );
            }
        }
    }

    public record VisibleFrame(
            List<PlayerObservation> players,
            boolean complete
    ) {
        public VisibleFrame {
            players = List.copyOf(
                    Objects.requireNonNull(players, "players")
            );
        }

        public static VisibleFrame complete(List<PlayerObservation> players) {
            return new VisibleFrame(players, true);
        }

        public static VisibleFrame incomplete(List<PlayerObservation> players) {
            return new VisibleFrame(players, false);
        }
    }

    public record Config(
            int operationBudget,
            int maximumTrackedPlayers,
            int maximumEntries,
            long lifetimeTicks,
            int offlineConfirmationChecks
    ) {
        public Config {
            if (operationBudget < 1 || operationBudget > 4_096) {
                throw new IllegalArgumentException(
                        "operationBudget must be between 1 and 4096"
                );
            }
            if (maximumTrackedPlayers < 1
                    || maximumTrackedPlayers > 4_096) {
                throw new IllegalArgumentException(
                        "maximumTrackedPlayers must be between 1 and 4096"
                );
            }
            if (maximumEntries < 1 || maximumEntries > 1_024) {
                throw new IllegalArgumentException(
                        "maximumEntries must be between 1 and 1024"
                );
            }
            if (lifetimeTicks < 1L || lifetimeTicks > 1_000_000L) {
                throw new IllegalArgumentException(
                        "lifetimeTicks must be between 1 and 1000000"
                );
            }
            if (offlineConfirmationChecks < 1
                    || offlineConfirmationChecks > 20) {
                throw new IllegalArgumentException(
                        "offlineConfirmationChecks must be between 1 and 20"
                );
            }
        }

        public static Config defaults() {
            return new Config(128, 512, 128, 18_000L, 2);
        }
    }

    public record LogoutSpotSnapshot(
            UUID playerId,
            String playerName,
            double x,
            double y,
            double z,
            float yaw,
            String dimension,
            long observedAtTick,
            long ageTicks,
            long remainingTicks
    ) {
        public LogoutSpotSnapshot {
            Objects.requireNonNull(playerId, "playerId");
            playerName = requireText(playerName, "playerName");
            dimension = requireText(dimension, "dimension");
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || !Float.isFinite(yaw)
                    || observedAtTick < 0L
                    || ageTicks < 0L
                    || remainingTicks < 0L) {
                throw new IllegalArgumentException("invalid logout snapshot");
            }
        }
    }

    public record TickResult(
            int operations,
            int visiblePlayersProcessed,
            int disappearanceChecks,
            int expiredEntries,
            int evictedTrackedPlayers,
            boolean frameAccepted,
            boolean baselineEstablished,
            int trackedPlayers,
            int retainedSpots,
            boolean sessionReset,
            int recordedSpots
    ) {
        private TickResult(
                int operations,
                int visiblePlayersProcessed,
                int disappearanceChecks,
                int expiredEntries,
                int evictedTrackedPlayers,
                boolean frameAccepted,
                boolean baselineEstablished,
                int trackedPlayers,
                int retainedSpots,
                boolean sessionReset
        ) {
            this(
                    operations,
                    visiblePlayersProcessed,
                    disappearanceChecks,
                    expiredEntries,
                    evictedTrackedPlayers,
                    frameAccepted,
                    baselineEstablished,
                    trackedPlayers,
                    retainedSpots,
                    sessionReset,
                    0
            );
        }
    }

    public record Status(
            SessionKey session,
            long lastTick,
            long frameSequence,
            boolean baselineEstablished,
            int trackedPlayers,
            int retainedSpots,
            int scanQueueSize
    ) {
    }

    private static final class TrackedPlayer {
        private final UUID playerId;
        private String playerName;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private long lastVisibleFrame;
        private long lastCheckedFrame;
        private int offlineConfirmations;
        private boolean loggedOut;

        private TrackedPlayer(
                UUID playerId,
                String playerName,
                double x,
                double y,
                double z,
                float yaw,
                long lastVisibleFrame,
                long lastCheckedFrame,
                int offlineConfirmations,
                boolean loggedOut
        ) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.lastVisibleFrame = lastVisibleFrame;
            this.lastCheckedFrame = lastCheckedFrame;
            this.offlineConfirmations = offlineConfirmations;
            this.loggedOut = loggedOut;
        }

        private void updateVisible(
                PlayerObservation observation,
                long frameSequence
        ) {
            playerName = observation.playerName();
            x = observation.x();
            y = observation.y();
            z = observation.z();
            yaw = observation.yaw();
            lastVisibleFrame = frameSequence;
            offlineConfirmations = 0;
            loggedOut = false;
        }
    }

    private static final class StoredSpot {
        private final UUID playerId;
        private final String playerName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final String dimension;
        private final long observedAtTick;

        private StoredSpot(
                UUID playerId,
                String playerName,
                double x,
                double y,
                double z,
                float yaw,
                String dimension,
                long observedAtTick
        ) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.dimension = dimension;
            this.observedAtTick = observedAtTick;
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
