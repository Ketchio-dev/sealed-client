package dev.b2tclient.v26.combat;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
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
 * <p>This class deliberately has no Minecraft dependencies. The platform
 * runtime owns the mapping from live client state to {@link SafetyContext} and
 * applies packets, keys, rotations, inventory clicks, and slot changes only
 * after resolution.</p>
 */
public final class CombatActionArbiter26 {
    private static final Comparator<Request> REQUEST_ORDER =
            Comparator.comparingInt(Request::priority)
                    .reversed()
                    .thenComparing(Request::owner);

    private final Map<String, Request> requests = new TreeMap<>();
    private final Map<Channel, Grant> grants = new EnumMap<>(Channel.class);
    private final Map<String, Decision> decisions = new TreeMap<>();
    private long tick;
    private Phase phase = Phase.IDLE;
    private SafetyBlock safetyBlock = SafetyBlock.NONE;

    /**
     * Opens the collection phase for the next client tick.
     *
     * <p>All previous grants and decisions expire here. An unsafe context
     * immediately closes the tick without granting any action.</p>
     */
    public void beginTick(SafetyContext context) {
        SafetyContext requestedContext = Objects.requireNonNull(context, "context");
        tick++;
        requests.clear();
        grants.clear();
        decisions.clear();
        safetyBlock = requestedContext.block();
        phase = safetyBlock == SafetyBlock.NONE
                ? Phase.COLLECTING
                : Phase.RESOLVED;
    }

    /**
     * Submits one atomic action bundle for this tick.
     *
     * @return {@code true} when collected; {@code false} when safety blocked
     *         the tick or the owner already submitted a request
     * @throws IllegalStateException when called before {@link #beginTick} or
     *         after a ready tick has already been resolved
     */
    public boolean submit(
            String owner,
            int priority,
            Set<Channel> channels
    ) {
        String requestedOwner = requireOwner(owner);
        Set<Channel> requestedChannels = immutableChannels(channels);
        if (safetyBlock != SafetyBlock.NONE) {
            return false;
        }
        requirePhase(Phase.COLLECTING, "submit");
        if (requests.containsKey(requestedOwner)) {
            return false;
        }
        Request request = new Request(
                requestedOwner,
                priority,
                requestedChannels
        );
        requests.put(requestedOwner, request);
        decisions.put(requestedOwner, Decision.pending(request));
        return true;
    }

    /**
     * Resolves every collected bundle exactly once.
     */
    public void resolve() {
        if (safetyBlock != SafetyBlock.NONE) {
            return;
        }
        requirePhase(Phase.COLLECTING, "resolve");
        requests.values().stream()
                .sorted(REQUEST_ORDER)
                .forEach(this::resolveRequest);
        phase = Phase.RESOLVED;
    }

    public boolean owns(String owner, Channel channel) {
        String requestedOwner = requireOwner(owner);
        Channel requestedChannel = Objects.requireNonNull(channel, "channel");
        if (phase != Phase.RESOLVED || safetyBlock != SafetyBlock.NONE) {
            return false;
        }
        Grant grant = grants.get(requestedChannel);
        return grant != null && grant.owner().equals(requestedOwner);
    }

    public boolean ownsAll(String owner, Set<Channel> channels) {
        String requestedOwner = requireOwner(owner);
        Set<Channel> requestedChannels = immutableChannels(channels);
        if (phase != Phase.RESOLVED || safetyBlock != SafetyBlock.NONE) {
            return false;
        }
        return requestedChannels.stream()
                .allMatch(channel -> {
                    Grant grant = grants.get(channel);
                    return grant != null
                            && grant.owner().equals(requestedOwner);
                });
    }

    /**
     * Returns the current-tick decision for an owner.
     *
     * <p>When safety has blocked collection, the returned decision identifies
     * the block even though no request was accepted.</p>
     */
    public Decision decision(String owner) {
        String requestedOwner = requireOwner(owner);
        Decision decision = decisions.get(requestedOwner);
        if (decision != null) {
            return decision;
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
        String requestedOwner = requireOwner(owner);
        Request request = requests.remove(requestedOwner);
        grants.entrySet().removeIf(entry ->
                entry.getValue().owner().equals(requestedOwner)
        );
        Decision previous = decisions.get(requestedOwner);
        if (previous != null || request != null) {
            Decision basis = previous != null
                    ? previous
                    : Decision.pending(request);
            decisions.put(requestedOwner, basis.released());
        }
    }

    /**
     * Cancels every pending and resolved action until the next tick.
     */
    public void releaseAll() {
        decisions.replaceAll((owner, decision) -> decision.released());
        requests.clear();
        grants.clear();
        if (phase != Phase.IDLE) {
            phase = Phase.RESOLVED;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                tick,
                phase,
                safetyBlock,
                immutableGrantMap(grants),
                Collections.unmodifiableMap(new TreeMap<>(decisions)),
                decisions.size()
        );
    }

    public long tick() {
        return tick;
    }

    private void resolveRequest(Request request) {
        Map<Channel, String> blockers = new EnumMap<>(Channel.class);
        for (Channel channel : request.channels()) {
            Grant grant = grants.get(channel);
            if (grant != null) {
                blockers.put(channel, grant.owner());
            }
        }
        if (!blockers.isEmpty()) {
            decisions.put(
                    request.owner(),
                    Decision.denied(request, blockers)
            );
            return;
        }
        for (Channel channel : request.channels()) {
            grants.put(
                    channel,
                    new Grant(request.owner(), request.priority())
            );
        }
        decisions.put(request.owner(), Decision.granted(request));
    }

    private void requirePhase(Phase required, String operation) {
        if (phase != required) {
            throw new IllegalStateException(
                    operation + " requires " + required
                            + " phase, current phase is " + phase
            );
        }
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Combat action owner cannot be blank");
        }
        String canonical = owner.trim();
        if (canonical.length() > 96) {
            throw new IllegalArgumentException(
                    "Combat action owner cannot exceed 96 characters"
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

    private record Request(
            String owner,
            int priority,
            Set<Channel> channels
    ) {
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

        private static Decision pending(Request request) {
            return new Decision(
                    DecisionStatus.PENDING,
                    request.priority(),
                    request.channels(),
                    Map.of(),
                    Optional.empty()
            );
        }

        private static Decision granted(Request request) {
            return new Decision(
                    DecisionStatus.GRANTED,
                    request.priority(),
                    request.channels(),
                    Map.of(),
                    Optional.empty()
            );
        }

        private static Decision denied(
                Request request,
                Map<Channel, String> blockers
        ) {
            return new Decision(
                    DecisionStatus.DENIED,
                    request.priority(),
                    request.channels(),
                    blockers,
                    Optional.empty()
            );
        }

        private Decision released() {
            return new Decision(
                    DecisionStatus.RELEASED,
                    priority,
                    requestedChannels,
                    Map.of(),
                    Optional.empty()
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
}
