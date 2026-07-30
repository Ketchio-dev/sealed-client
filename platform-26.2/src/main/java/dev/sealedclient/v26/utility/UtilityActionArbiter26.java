package dev.sealedclient.v26.utility;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic, per-tick arbitration for Minecraft 26.2 utility services.
 *
 * <p>Every service submits its complete channel bundle before the runtime
 * calls {@link #resolve()}. A bundle is either granted in full or denied in
 * full, so an action can never rotate without using an item or switch a
 * hotbar slot without owning the matching use channel.</p>
 *
 * <p>The runtime may reserve channels already granted to combat or movement
 * by passing them to {@link #beginTick(SafetyContext, Set)}. Reservations are
 * never pre-empted. This is the explicit bridge between the three arbiters and
 * prevents independently resolved utility work from colliding with a combat
 * action in the same client tick.</p>
 */
public final class UtilityActionArbiter26 {
    public static final String EXTERNAL_OWNER = "@external";

    private static final int MAXIMUM_OWNER_LENGTH = 96;
    private static final Comparator<Request> REQUEST_ORDER =
            Comparator.comparingInt(Request::priority)
                    .reversed()
                    .thenComparing(Request::owner);

    private final Map<String, Request> requests = new TreeMap<>();
    private final Map<Channel, Grant> grants =
            new EnumMap<>(Channel.class);
    private final Map<String, Decision> decisions = new TreeMap<>();
    private final Set<Channel> reservedChannels =
            EnumSet.noneOf(Channel.class);
    private long tick;
    private Phase phase = Phase.IDLE;
    private SafetyBlock safetyBlock = SafetyBlock.NONE;

    public void beginTick(SafetyContext context) {
        beginTick(context, Set.of());
    }

    /**
     * Opens collection and atomically installs external channel reservations.
     */
    public void beginTick(
            SafetyContext context,
            Set<Channel> externalReservations
    ) {
        SafetyContext requested = Objects.requireNonNull(context, "context");
        Set<Channel> reservations = immutableChannels(
                externalReservations,
                true
        );
        tick++;
        requests.clear();
        grants.clear();
        decisions.clear();
        reservedChannels.clear();
        reservedChannels.addAll(reservations);
        safetyBlock = requested.block();
        phase = safetyBlock == SafetyBlock.NONE
                ? Phase.COLLECTING
                : Phase.RESOLVED;
    }

    public boolean submit(
            String owner,
            int priority,
            Set<Channel> channels
    ) {
        String requestedOwner = requireOwner(owner);
        if (EXTERNAL_OWNER.equals(requestedOwner)) {
            throw new IllegalArgumentException(
                    "External reservation owner is reserved"
            );
        }
        Set<Channel> requestedChannels = immutableChannels(channels, false);
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

    public void resolve() {
        if (safetyBlock != SafetyBlock.NONE) {
            return;
        }
        requirePhase(Phase.COLLECTING, "resolve");
        for (Channel channel : reservedChannels) {
            grants.put(channel, new Grant(EXTERNAL_OWNER, Integer.MAX_VALUE));
        }
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
        Set<Channel> requestedChannels = immutableChannels(channels, false);
        if (phase != Phase.RESOLVED || safetyBlock != SafetyBlock.NONE) {
            return false;
        }
        return requestedChannels.stream().allMatch(
                channel -> owns(requestedOwner, channel)
        );
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

    public void releaseOwner(String owner) {
        String requestedOwner = requireOwner(owner);
        Request request = requests.remove(requestedOwner);
        grants.entrySet().removeIf(entry ->
                !EXTERNAL_OWNER.equals(entry.getValue().owner())
                        && entry.getValue().owner().equals(requestedOwner)
        );
        Decision previous = decisions.get(requestedOwner);
        if (previous != null || request != null) {
            Decision basis = previous != null
                    ? previous
                    : Decision.pending(request);
            decisions.put(requestedOwner, basis.released());
        }
    }

    public void releaseAll() {
        decisions.replaceAll((owner, decision) -> decision.released());
        requests.clear();
        grants.entrySet().removeIf(entry ->
                !EXTERNAL_OWNER.equals(entry.getValue().owner())
        );
        if (phase != Phase.IDLE) {
            phase = Phase.RESOLVED;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                tick,
                phase,
                safetyBlock,
                immutableChannels(reservedChannels, true),
                immutableGrantMap(grants),
                Collections.unmodifiableMap(new TreeMap<>(decisions))
        );
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
                    "Utility action owner cannot be blank"
            );
        }
        String canonical = owner.trim();
        if (canonical.length() > MAXIMUM_OWNER_LENGTH) {
            throw new IllegalArgumentException(
                    "Utility action owner cannot exceed "
                            + MAXIMUM_OWNER_LENGTH + " characters"
            );
        }
        return canonical;
    }

    private static Set<Channel> immutableChannels(
            Set<Channel> channels,
            boolean allowEmpty
    ) {
        Objects.requireNonNull(channels, "channels");
        if (!allowEmpty && channels.isEmpty()) {
            throw new IllegalArgumentException(
                    "Utility action requires at least one channel"
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
        EnumMap<Channel, Grant> copy = new EnumMap<>(Channel.class);
        copy.putAll(Objects.requireNonNull(source, "channelGrants"));
        return Collections.unmodifiableMap(copy);
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

    private record Request(
            String owner,
            int priority,
            Set<Channel> channels
    ) {
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

        private static Decision pending(Request request) {
            return new Decision(
                    DecisionStatus.PENDING,
                    request.priority(),
                    request.channels(),
                    Map.of(),
                    SafetyBlock.NONE
            );
        }

        private static Decision granted(Request request) {
            return new Decision(
                    DecisionStatus.GRANTED,
                    request.priority(),
                    request.channels(),
                    Map.of(),
                    SafetyBlock.NONE
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
                    SafetyBlock.NONE
            );
        }

        private Decision released() {
            return new Decision(
                    DecisionStatus.RELEASED,
                    priority,
                    requestedChannels,
                    Map.of(),
                    SafetyBlock.NONE
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
}
