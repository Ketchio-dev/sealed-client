package dev.sealedclient.v26.movement;

import dev.sealedclient.common.arbitration.ActionArbiter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic two-phase arbitration for Minecraft 26.2 movement services.
 *
 * <p>Services submit every channel needed by one action before the runtime
 * calls {@link #resolve()}. A request either receives its complete bundle or
 * receives nothing, preventing partial actions such as an inventory swap
 * without the matching hotbar or packet operation.</p>
 *
 * <p>Priority is descending and equal-priority requests are ordered by their
 * canonical owner identifier. Consequently, submission and service tick order
 * cannot change the result. Every request and grant expires at the next
 * {@link #beginTick(SafetyContext)} call.</p>
 *
 * <p>The arbitration itself lives in {@link ActionArbiter}; this class binds it
 * to the movement channel set and keeps the movement-specific safety
 * vocabulary. It intentionally has no Minecraft dependencies: live client state
 * is converted to {@link SafetyContext} by the platform runtime and actual
 * velocity, key, rotation, packet, hotbar, and inventory changes happen only
 * after resolution.</p>
 */
public final class MovementActionArbiter26 {
    private static final int MAXIMUM_OWNER_LENGTH = ActionArbiter.MAXIMUM_OWNER_LENGTH;

    private final ActionArbiter<Channel> arbiter =
            new ActionArbiter<>(Channel.class, "Movement");
    private SafetyBlock safetyBlock = SafetyBlock.NONE;

    /**
     * Opens the collection phase for the next client tick.
     */
    public void beginTick(SafetyContext context) {
        Objects.requireNonNull(context, "context");
        safetyBlock = context.block();
        arbiter.beginTick(safetyBlock != SafetyBlock.NONE, Set.of());
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
                channelGrants,
                decisions,
                arbiter.submittedCount()
        );
    }

    public long tick() {
        return arbiter.tick();
    }

    /**
     * Independently contended movement side effects.
     */
    public enum Channel {
        HORIZONTAL,
        VERTICAL,
        KEY_INPUT,
        ROTATION,
        PACKET,
        HOTBAR,
        INVENTORY
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
        SCREEN_OPEN,
        NETWORK_PAUSED
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
            boolean screenClear,
            boolean networkReady
    ) {
        public static SafetyContext ready() {
            return new SafetyContext(true, true, true, true, true);
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
            if (!screenClear) {
                return SafetyBlock.SCREEN_OPEN;
            }
            if (!networkReady) {
                return SafetyBlock.NETWORK_PAUSED;
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
            Optional<SafetyBlock> safetyBlock
    ) {
        public Decision {
            status = Objects.requireNonNull(status, "status");
            Objects.requireNonNull(requestedChannels, "requestedChannels");
            requestedChannels = requestedChannels.isEmpty()
                    ? Set.of()
                    : immutableChannels(requestedChannels);
            blockers = immutableBlockerMap(blockers);
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
                    Optional.empty()
            );
        }

        private static Decision notSubmitted() {
            return new Decision(
                    DecisionStatus.NOT_SUBMITTED,
                    0,
                    Set.of(),
                    Map.of(),
                    Optional.empty()
            );
        }

        private static Decision safetyBlocked(SafetyBlock block) {
            return new Decision(
                    DecisionStatus.SAFETY_BLOCKED,
                    0,
                    Set.of(),
                    Map.of(),
                    Optional.of(block)
            );
        }

        private static Map<Channel, String> immutableBlockerMap(
                Map<Channel, String> source
        ) {
            Objects.requireNonNull(source, "blockers");
            EnumMap<Channel, String> copy = new EnumMap<>(Channel.class);
            source.forEach((channel, owner) -> copy.put(
                    Objects.requireNonNull(channel, "blocker channel"),
                    requireOwner(owner)
            ));
            return Collections.unmodifiableMap(copy);
        }
    }

    public record Snapshot(
            long tick,
            Phase phase,
            SafetyBlock safetyBlock,
            Map<Channel, Grant> channelGrants,
            Map<String, Decision> decisions,
            int submittedCount
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
            channelGrants = immutableGrantMap(channelGrants);
            decisions = Collections.unmodifiableMap(
                    new TreeMap<>(Objects.requireNonNull(
                            decisions,
                            "decisions"
                    ))
            );
            if (submittedCount < 0) {
                throw new IllegalArgumentException(
                        "submittedCount cannot be negative"
                );
            }
        }
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException(
                    "Movement action owner cannot be blank"
            );
        }
        String canonical = owner.trim();
        if (canonical.length() > MAXIMUM_OWNER_LENGTH) {
            throw new IllegalArgumentException(
                    "Movement action owner cannot exceed "
                            + MAXIMUM_OWNER_LENGTH + " characters"
            );
        }
        return canonical;
    }

    private static Set<Channel> immutableChannels(Set<Channel> channels) {
        Objects.requireNonNull(channels, "channels");
        if (channels.isEmpty()) {
            throw new IllegalArgumentException(
                    "Movement action requires at least one channel"
            );
        }
        EnumSet<Channel> copy = EnumSet.noneOf(Channel.class);
        for (Channel channel : channels) {
            copy.add(Objects.requireNonNull(channel, "channel"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<Channel, Grant> immutableGrantMap(
            Map<Channel, Grant> source
    ) {
        Objects.requireNonNull(source, "channelGrants");
        EnumMap<Channel, Grant> copy = new EnumMap<>(Channel.class);
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }
}
