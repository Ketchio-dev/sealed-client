package dev.sealedclient.v26.utility;

import dev.sealedclient.common.arbitration.ActionArbiter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic two-phase arbitration for Minecraft 26.2 utility services.
 *
 * <p>Services submit the full channel bundle one action needs, and after
 * {@link #resolve()} may act only on a bundle they own completely. Utility runs
 * after combat and movement, so channels those subsystems already committed are
 * installed as external reservations at {@link #beginTick} and are held by
 * {@link #EXTERNAL_OWNER} for the whole tick.</p>
 *
 * <p>The arbitration itself lives in {@link ActionArbiter}; this class binds it
 * to the utility channel set and keeps the utility-specific safety vocabulary.
 * It has no Minecraft dependencies.</p>
 */
public final class UtilityActionArbiter26 {
    /** Owner identifier for channels reserved by combat, movement or Baritone. */
    public static final String EXTERNAL_OWNER = ActionArbiter.EXTERNAL_OWNER;
    private static final int MAXIMUM_OWNER_LENGTH = ActionArbiter.MAXIMUM_OWNER_LENGTH;

    private final ActionArbiter<Channel> arbiter =
            new ActionArbiter<>(Channel.class, "Utility");
    private SafetyBlock safetyBlock = SafetyBlock.NONE;

    public void beginTick(SafetyContext context) {
        beginTick(context, Set.of());
    }

    /**
     * Opens collection and atomically installs external channel reservations.
     */
    public void beginTick(SafetyContext context, Set<Channel> externalReservations) {
        Objects.requireNonNull(context, "context");
        safetyBlock = context.block();
        arbiter.beginTick(safetyBlock != SafetyBlock.NONE, externalReservations);
    }

    public boolean submit(String owner, int priority, Set<Channel> channels) {
        return arbiter.submit(owner, priority, channels);
    }

    public void resolve() {
        arbiter.resolve();
    }

    public boolean owns(String owner, Channel channel) {
        return arbiter.owns(owner, channel);
    }

    public boolean ownsAll(String owner, Set<Channel> channels) {
        return arbiter.ownsAll(owner, channels);
    }

    public Decision decision(String owner) {
        ActionArbiter.Decision<Channel> decision = arbiter.decision(owner);
        if (decision != null) {
            return Decision.of(decision);
        }
        if (safetyBlock != SafetyBlock.NONE) {
            return Decision.safetyBlocked(safetyBlock);
        }
        return Decision.notSubmitted();
    }

    public void releaseOwner(String owner) {
        arbiter.releaseOwner(owner);
    }

    public void releaseAll() {
        arbiter.releaseAll();
    }

    public Snapshot snapshot() {
        Map<Channel, Grant> channelGrants = new EnumMap<>(Channel.class);
        arbiter.grants().forEach((channel, grant) ->
                channelGrants.put(channel, new Grant(grant.owner(), grant.priority()))
        );
        Map<String, Decision> decisions = new TreeMap<>();
        arbiter.decisions().forEach((owner, decision) ->
                decisions.put(owner, Decision.of(decision))
        );
        return new Snapshot(
                arbiter.tick(),
                Phase.valueOf(arbiter.phase().name()),
                safetyBlock,
                arbiter.reservedChannels(),
                channelGrants,
                decisions
        );
    }

    public long tick() {
        return arbiter.tick();
    }

    public enum Channel {
        USE,
        HOTBAR,
        INVENTORY,
        ROTATION
    }

    public enum Phase {
        IDLE,
        COLLECTING,
        RESOLVED
    }

    public enum SafetyBlock {
        NONE,
        NO_SESSION,
        NO_PLAYER,
        PLAYER_DEAD,
        NETWORK_UNREADY
    }

    public enum DecisionStatus {
        NOT_SUBMITTED,
        PENDING,
        GRANTED,
        DENIED,
        RELEASED,
        SAFETY_BLOCKED
    }

    public record SafetyContext(
            boolean sessionActive,
            boolean playerPresent,
            boolean playerAlive,
            boolean networkReady
    ) {
        public static SafetyContext ready() {
            return new SafetyContext(true, true, true, true);
        }

        public SafetyBlock block() {
            if (!sessionActive) {
                return SafetyBlock.NO_SESSION;
            }
            if (!playerPresent) {
                return SafetyBlock.NO_PLAYER;
            }
            if (!playerAlive) {
                return SafetyBlock.PLAYER_DEAD;
            }
            if (!networkReady) {
                return SafetyBlock.NETWORK_UNREADY;
            }
            return SafetyBlock.NONE;
        }
    }

    public record Grant(String owner, int priority) {
        public Grant {
            owner = requireOwner(owner);
        }
    }

    public record Decision(
            DecisionStatus status,
            int priority,
            Set<Channel> requestedChannels,
            Map<Channel, String> blockers,
            SafetyBlock safetyBlock
    ) {
        public Decision {
            status = Objects.requireNonNull(status, "status");
            requestedChannels = requestedChannels.isEmpty()
                    ? Set.of()
                    : immutableChannels(requestedChannels, false);
            EnumMap<Channel, String> blockerCopy =
                    new EnumMap<>(Channel.class);
            Objects.requireNonNull(blockers, "blockers")
                    .forEach((channel, owner) -> blockerCopy.put(
                            Objects.requireNonNull(channel, "blocker channel"),
                            requireOwner(owner)
                    ));
            blockers = Collections.unmodifiableMap(blockerCopy);
            safetyBlock = Objects.requireNonNull(
                    safetyBlock,
                    "safetyBlock"
            );
        }

        public boolean granted() {
            return status == DecisionStatus.GRANTED;
        }

        private static Decision of(ActionArbiter.Decision<Channel> decision) {
            return new Decision(
                    DecisionStatus.valueOf(decision.status().name()),
                    decision.priority(),
                    decision.requestedChannels(),
                    decision.blockers(),
                    SafetyBlock.NONE
            );
        }

        private static Decision notSubmitted() {
            return new Decision(
                    DecisionStatus.NOT_SUBMITTED,
                    0,
                    Set.of(),
                    Map.of(),
                    SafetyBlock.NONE
            );
        }

        private static Decision safetyBlocked(SafetyBlock block) {
            return new Decision(
                    DecisionStatus.SAFETY_BLOCKED,
                    0,
                    Set.of(),
                    Map.of(),
                    block
            );
        }
    }

    public record Snapshot(
            long tick,
            Phase phase,
            SafetyBlock safetyBlock,
            Set<Channel> reservedChannels,
            Map<Channel, Grant> channelGrants,
            Map<String, Decision> decisions
    ) {
        public Snapshot {
            if (tick < 0L) {
                throw new IllegalArgumentException("tick cannot be negative");
            }
            phase = Objects.requireNonNull(phase, "phase");
            safetyBlock = Objects.requireNonNull(
                    safetyBlock,
                    "safetyBlock"
            );
            reservedChannels = immutableChannels(
                    reservedChannels,
                    true
            );
            channelGrants = immutableGrantMap(channelGrants);
            decisions = Collections.unmodifiableMap(
                    new TreeMap<>(Objects.requireNonNull(
                            decisions,
                            "decisions"
                    ))
            );
        }
    }

    private static String requireOwner(String owner) {
        return ActionArbiter.requireOwner(owner, "Utility");
    }

    private static Set<Channel> immutableChannels(
            Set<Channel> channels,
            boolean allowEmpty
    ) {
        return ActionArbiter.copyChannels(channels, Channel.class, "Utility", allowEmpty);
    }

    private static Map<Channel, Grant> immutableGrantMap(
            Map<Channel, Grant> source
    ) {
        return ActionArbiter.copyGrants(source, Channel.class);
    }
}
