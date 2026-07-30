package dev.b2tclient.v26.hud;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks authoritative local totem activations.
 *
 * <p>Inventory decreases are deliberately not counted. A pop is accepted only
 * for the local player's {@code PROTECTED_FROM_DEATH} entity event. Health,
 * liveness, and offhand transitions maintain reset/display state but never
 * create a second inferred count. Each authoritative event counts because the
 * protocol provides no safe application-level duplicate identity.</p>
 */
public final class LocalTotemPopTracker26 {
    public static final int MAX_POP_COUNT = 9_999;
    public static final long RECENT_DISPLAY_NANOS = 15_000_000_000L;

    private final LongSupplier nanoTime;

    private boolean connected;
    private UUID playerUuid;
    private int entityId;
    private boolean hasState;
    private boolean alive;
    private float health;
    private int offhandTotems;
    private long stateRevision;
    private int popCount;
    private boolean saturated;
    private long lastPopNanos;

    public LocalTotemPopTracker26() {
        this(System::nanoTime);
    }

    LocalTotemPopTracker26(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public synchronized void connect(UUID playerUuid, int entityId) {
        connect(playerUuid, entityId, nanoTime.getAsLong());
    }

    public synchronized void connect(
            UUID playerUuid,
            int entityId,
            long nowNanos
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        clear();
        connected = true;
        this.playerUuid = playerUuid;
        this.entityId = entityId;
        lastPopNanos = nowNanos;
    }

    /**
     * Publishes the local player's current state without inferring a pop from
     * any inventory transition.
     *
     * @return true when the observation belonged to the active local player
     */
    public synchronized boolean observeState(
            UUID observedUuid,
            int observedEntityId,
            float observedHealth,
            int observedOffhandTotems,
            boolean observedAlive
    ) {
        return observeState(
                observedUuid,
                observedEntityId,
                observedHealth,
                observedOffhandTotems,
                observedAlive,
                nanoTime.getAsLong()
        );
    }

    public synchronized boolean observeState(
            UUID observedUuid,
            int observedEntityId,
            float observedHealth,
            int observedOffhandTotems,
            boolean observedAlive,
            long nowNanos
    ) {
        if (!matches(observedUuid, observedEntityId)
                || !validState(observedHealth, observedOffhandTotems)) {
            return false;
        }
        updateState(
                observedHealth,
                observedOffhandTotems,
                observedAlive,
                nowNanos,
                false
        );
        return true;
    }

    public synchronized EventResult observeProtectedFromDeath(
            UUID observedUuid,
            int observedEntityId,
            float observedHealth,
            int observedOffhandTotems,
            boolean observedAlive
    ) {
        return observeProtectedFromDeath(
                observedUuid,
                observedEntityId,
                observedHealth,
                observedOffhandTotems,
                observedAlive,
                nanoTime.getAsLong()
        );
    }

    public synchronized EventResult observeProtectedFromDeath(
            UUID observedUuid,
            int observedEntityId,
            float observedHealth,
            int observedOffhandTotems,
            boolean observedAlive,
            long nowNanos
    ) {
        if (!connected) {
            return EventResult.DISCONNECTED;
        }
        if (!matches(observedUuid, observedEntityId)) {
            return EventResult.WRONG_PLAYER;
        }
        if (!validState(observedHealth, observedOffhandTotems)) {
            return EventResult.INVALID_STATE;
        }

        updateState(
                observedHealth,
                observedOffhandTotems,
                observedAlive,
                nowNanos,
                true
        );
        if (popCount < MAX_POP_COUNT) {
            popCount++;
        } else {
            saturated = true;
        }
        lastPopNanos = nowNanos;
        return EventResult.ACCEPTED;
    }

    public synchronized void disconnect() {
        clear();
    }

    public synchronized Snapshot snapshot() {
        return snapshot(nanoTime.getAsLong());
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        if (!connected) {
            return new Snapshot(
                    Status.DISCONNECTED,
                    0,
                    false,
                    false,
                    -1L,
                    Float.NaN,
                    -1,
                    0L
            );
        }
        long ageMillis = popCount == 0
                ? -1L
                : positiveDifference(nowNanos, lastPopNanos) / 1_000_000L;
        boolean recent = popCount > 0
                && nowNanos >= lastPopNanos
                && positiveDifference(nowNanos, lastPopNanos)
                <= RECENT_DISPLAY_NANOS;
        return new Snapshot(
                !hasState
                        ? Status.MEASURING
                        : (alive ? Status.TRACKING : Status.DEAD),
                popCount,
                saturated,
                recent,
                ageMillis,
                hasState ? health : Float.NaN,
                hasState ? offhandTotems : -1,
                stateRevision
        );
    }

    private void updateState(
            float observedHealth,
            int observedOffhandTotems,
            boolean observedAlive,
            long nowNanos,
            boolean protectedFromDeathEvidence
    ) {
        boolean effectivelyAlive = protectedFromDeathEvidence
                || observedAlive && observedHealth > 0.0F;
        if (!hasState
                || Float.compare(health, observedHealth) != 0
                || offhandTotems != observedOffhandTotems
                || alive != effectivelyAlive) {
            stateRevision++;
        }
        boolean died = hasState && alive && !effectivelyAlive;
        hasState = true;
        health = observedHealth;
        offhandTotems = observedOffhandTotems;
        alive = effectivelyAlive;
        if (died) {
            resetCount(nowNanos);
        }
    }

    private boolean matches(UUID observedUuid, int observedEntityId) {
        return connected
                && playerUuid.equals(observedUuid)
                && entityId == observedEntityId;
    }

    private static boolean validState(float health, int offhandTotems) {
        return Float.isFinite(health)
                && health >= 0.0F
                && offhandTotems >= 0
                && offhandTotems <= 64;
    }

    private void resetCount(long nowNanos) {
        popCount = 0;
        saturated = false;
        lastPopNanos = nowNanos;
    }

    private static long positiveDifference(long later, long earlier) {
        if (later <= earlier) {
            return 0L;
        }
        long difference = later - earlier;
        return difference < 0L ? Long.MAX_VALUE : difference;
    }

    private void clear() {
        connected = false;
        playerUuid = null;
        entityId = 0;
        hasState = false;
        alive = false;
        health = 0.0F;
        offhandTotems = 0;
        stateRevision = 0L;
        resetCount(0L);
    }

    public enum EventResult {
        ACCEPTED,
        IGNORED_EVENT,
        WRONG_PLAYER,
        INVALID_STATE,
        DISCONNECTED
    }

    public enum Status {
        DISCONNECTED,
        MEASURING,
        TRACKING,
        DEAD
    }

    public record Snapshot(
            Status status,
            int popCount,
            boolean saturated,
            boolean recentPop,
            long lastPopAgeMillis,
            float health,
            int offhandTotems,
            long stateRevision
    ) {
        public Snapshot {
            Objects.requireNonNull(status, "status");
            if (popCount < 0
                    || popCount > MAX_POP_COUNT
                    || lastPopAgeMillis < -1L
                    || offhandTotems < -1
                    || offhandTotems > 64
                    || stateRevision < 0L) {
                throw new IllegalArgumentException(
                        "Invalid local totem-pop snapshot"
                );
            }
        }

        public String displayText() {
            if (status == Status.DISCONNECTED) {
                return "Local pops --";
            }
            return String.format(
                    Locale.ROOT,
                    "Local pops %d%s",
                    popCount,
                    saturated ? "+" : ""
            );
        }
    }
}
