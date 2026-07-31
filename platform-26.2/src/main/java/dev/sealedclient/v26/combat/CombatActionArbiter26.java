package dev.sealedclient.v26.combat;

import dev.sealedclient.common.arbitration.ActionArbiter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Two-phase, per-tick arbitration for Minecraft 26.2 combat services.
 *
 * <p>Every service first submits the complete channel bundle required by one
 * action. After all submissions, the runtime calls {@link #resolve()}; services
 * may execute only bundles for which {@link #ownsAll(String, Set)} returns
 * {@code true}. Resolving complete bundles prevents partial actions such as a
 * hotbar switch without the corresponding use or attack.</p>
 *
 * <p>Resolution is independent of service tick order: higher priorities win,
 * and equal priorities are ordered by the canonical owner identifier using
 * {@link String#compareTo(String)}. One owner identifier may submit at most
 * once per tick. A service with multiple candidate actions should use stable
 * action identifiers such as {@code auto_crystal.break} and
 * {@code auto_crystal.place}.</p>
 *
 * <p>The arbitration itself lives in {@link ActionArbiter}; this class binds it
 * to the combat channel set and keeps the combat-specific safety vocabulary.
 * It deliberately has no Minecraft dependencies: the platform runtime owns the
 * mapping from live client state to {@link SafetyContext} and applies packets,
 * keys, rotations, inventory clicks, and slot changes only after resolution.</p>
 */
public final class CombatActionArbiter26 {
    private final ActionArbiter<Channel> arbiter =
            new ActionArbiter<>(Channel.class, "Combat");
    private SafetyBlock safetyBlock = SafetyBlock.NONE;

    /**
     * Opens the collection phase for the next client tick.
     *
     * <p>All previous grants and decisions expire here. An unsafe context
     * immediately closes the tick without granting any action.</p>
     */
    public void beginTick(SafetyContext context) {
        Objects.requireNonNull(context, "context");
        safetyBlock = context.block();
        arbiter.beginTick(safetyBlock != SafetyBlock.NONE, Set.of());
    }

    /**
     * Submits one atomic action bundle for this tick.
     *
     * @return {@code true} when collected; {@code false} when safety blocked
     *         the tick or the owner already submitted a request
     * @throws IllegalStateException when called before {@link #beginTick} or
     *         after a ready tick has already been resolved
     */
    public boolean submit(String owner, int priority, Set<Channel> channels) {
        return arbiter.submit(owner, priority, channels);
    }

    /**
     * Resolves every collected bundle exactly once.
     */
    public void resolve() {
        arbiter.resolve();
    }

    public boolean owns(String owner, Channel channel) {
        return arbiter.owns(owner, channel);
    }

    public boolean ownsAll(String owner, Set<Channel> channels) {
        return arbiter.ownsAll(owner, channels);
    }

    /**
     * Returns the current-tick decision for an owner.
     *
     * <p>When safety has blocked collection, the returned decision identifies
     * the block even though no request was accepted.</p>
     */
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

    /**
     * Releases an owner's pending request and all current grants.
     *
     * <p>A release after resolution never promotes a previously denied
     * request. That property keeps execution deterministic and prevents an
     * action from becoming authorized after its service already observed a
     * denial.</p>
     */
    public void releaseOwner(String owner) {
        arbiter.releaseOwner(owner);
    }

    /**
     * Cancels every pending and resolved action until the next tick.
     */
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

    public enum Channel {
        ATTACK,
        USE,
        HOTBAR,
        INVENTORY,
        ROTATION,
        MOVEMENT
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
        SCREEN_OPEN
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
            boolean screenClear
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
            if (!screenClear) {
                return SafetyBlock.SCREEN_OPEN;
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
            requestedChannels = requestedChannels.isEmpty()
                    ? Set.of()
                    : immutableChannels(requestedChannels);
            blockers = immutableBlockerMap(blockers);
            safetyBlock = Objects.requireNonNull(safetyBlock, "safetyBlock");
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
            if (tick < 0) {
                throw new IllegalArgumentException("tick cannot be negative");
            }
            phase = Objects.requireNonNull(phase, "phase");
            safetyBlock = Objects.requireNonNull(safetyBlock, "safetyBlock");
            channelGrants = immutableGrantMap(channelGrants);
            decisions = Collections.unmodifiableMap(new TreeMap<>(decisions));
            if (submittedCount < 0) {
                throw new IllegalArgumentException(
                        "submittedCount cannot be negative"
                );
            }
        }
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Combat action owner cannot be blank");
        }
        String canonical = owner.trim();
        if (canonical.length() > ActionArbiter.MAXIMUM_OWNER_LENGTH) {
            throw new IllegalArgumentException(
                    "Combat action owner cannot exceed "
                            + ActionArbiter.MAXIMUM_OWNER_LENGTH + " characters"
            );
        }
        return canonical;
    }

    private static Set<Channel> immutableChannels(Set<Channel> channels) {
        Objects.requireNonNull(channels, "channels");
        if (channels.isEmpty()) {
            throw new IllegalArgumentException(
                    "Combat action requires at least one channel"
            );
        }
        java.util.EnumSet<Channel> copy = java.util.EnumSet.noneOf(Channel.class);
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
