package dev.b2tclient.v26.movement;

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
 * <p>This class intentionally has no Minecraft dependencies. Live client state
 * is converted to {@link SafetyContext} by the platform runtime and actual
 * velocity, key, rotation, packet, hotbar, and inventory changes happen only
 * after resolution.</p>
 */
public final class MovementActionArbiter26 {
    private static final int MAXIMUM_OWNER_LENGTH = 96;
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
     * Opens collection for a new client tick and expires all previous state.
     *
     * <p>An unsafe context leaves the tick resolved with no grants. Calls to
     * {@link #decision(String)} still expose the precise safety reason.</p>
     */
    public void beginTick(SafetyContext context) {
        SafetyContext requested = Objects.requireNonNull(context, "context");
        tick++;
        requests.clear();
        grants.clear();
        decisions.clear();
        safetyBlock = requested.block();
        phase = safetyBlock == SafetyBlock.NONE
                ? Phase.COLLECTING
                : Phase.RESOLVED;
    }

    /**
     * Submits one atomic action bundle.
     *
     * @return {@code true} if collected, or {@code false} if the tick is
     *         safety-blocked or the owner already submitted this tick
     */
    public boolean submit(String owner, int priority, Set<Channel> channels) {
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
     * Resolves all collected requests exactly once.
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
        return requestedChannels.stream().allMatch(channel -> {
            Grant grant = grants.get(channel);
            return grant != null && grant.owner().equals(requestedOwner);
        });
    }

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
     * Releases one owner's request and complete grant bundle.
     *
     * <p>A denied request is never promoted after release, so services cannot
     * gain authority after already observing a denial.</p>
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
     * Cancels every action until the next tick.
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

    private record Request(
            String owner,
            int priority,
            Set<Channel> channels
    ) {
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
            return requestDecision(DecisionStatus.PENDING, request, Map.of());
        }

        private static Decision granted(Request request) {
            return requestDecision(DecisionStatus.GRANTED, request, Map.of());
        }

        private static Decision denied(
                Request request,
                Map<Channel, String> blockers
        ) {
            return requestDecision(
                    DecisionStatus.DENIED,
                    request,
                    blockers
            );
        }

        private static Decision requestDecision(
                DecisionStatus status,
                Request request,
                Map<Channel, String> blockers
        ) {
            return new Decision(
                    status,
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
}
