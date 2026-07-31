package dev.sealedclient.common.arbitration;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Two-phase, per-tick action arbitration over an arbitrary channel enum.
 *
 * <p>A service submits the complete channel bundle one action needs. After all
 * submissions the runtime resolves once, and a service may act only when it
 * owns every channel it asked for. Granting bundles rather than individual
 * channels is what prevents half-executed actions, such as a hotbar switch
 * without the matching use, or an item use without the rotation that was
 * supposed to aim it.</p>
 *
 * <p>Resolution does not depend on submission order: higher priorities win and
 * equal priorities are ordered by owner identifier. Running the same tick with
 * services in a different order therefore produces the same grants.</p>
 *
 * <p>Channels reserved externally are granted to {@link #EXTERNAL_OWNER} before
 * any request is considered, which is how one arbiter blocks another from
 * touching a channel it has already committed.</p>
 *
 * <p>This class has no Minecraft dependencies. Callers map live client state to
 * a blocked flag and apply real effects only after resolution.</p>
 */
public final class ActionArbiter<C extends Enum<C>> {
    /** Owner identifier held by channels reserved outside this arbiter. */
    public static final String EXTERNAL_OWNER = "@external";
    public static final int MAXIMUM_OWNER_LENGTH = 96;

    private final Class<C> channelType;
    private final String label;
    private final Comparator<Request<C>> requestOrder =
            Comparator.comparingInt((Request<C> request) -> request.priority())
                    .reversed()
                    .thenComparing(Request::owner);

    private final Map<String, Request<C>> requests = new TreeMap<>();
    private final Map<C, Grant> grants;
    private final Map<String, Decision<C>> decisions = new TreeMap<>();
    private final Set<C> reservedChannels;

    private long tick;
    private Phase phase = Phase.IDLE;
    private boolean blocked;

    /**
     * @param label human-readable subsystem name used in error messages
     */
    public ActionArbiter(Class<C> channelType, String label) {
        this.channelType = Objects.requireNonNull(channelType, "channelType");
        this.label = Objects.requireNonNull(label, "label");
        this.grants = new EnumMap<>(channelType);
        this.reservedChannels = EnumSet.noneOf(channelType);
    }

    /**
     * Opens collection for the next tick and installs external reservations.
     *
     * <p>Every previous grant and decision expires here. A blocked tick is
     * closed immediately so that nothing can be granted.</p>
     */
    public void beginTick(boolean safetyBlocked, Set<C> externalReservations) {
        Set<C> reservations = copyChannels(externalReservations, true);
        tick++;
        requests.clear();
        grants.clear();
        decisions.clear();
        reservedChannels.clear();
        reservedChannels.addAll(reservations);
        blocked = safetyBlocked;
        phase = blocked ? Phase.RESOLVED : Phase.COLLECTING;
    }

    /**
     * Submits one atomic bundle.
     *
     * @return {@code false} when the tick is blocked or the owner already
     *         submitted this tick
     */
    public boolean submit(String owner, int priority, Set<C> channels) {
        String canonicalOwner = requireOwner(owner);
        if (EXTERNAL_OWNER.equals(canonicalOwner)) {
            throw new IllegalArgumentException(
                    "External reservation owner is reserved"
            );
        }
        Set<C> requested = copyChannels(channels, false);
        if (blocked) {
            return false;
        }
        requirePhase(Phase.COLLECTING, "submit");
        if (requests.containsKey(canonicalOwner)) {
            return false;
        }
        Request<C> request = new Request<>(canonicalOwner, priority, requested);
        requests.put(canonicalOwner, request);
        decisions.put(canonicalOwner, Decision.pending(request));
        return true;
    }

    /** Resolves every collected bundle exactly once. */
    public void resolve() {
        if (blocked) {
            return;
        }
        requirePhase(Phase.COLLECTING, "resolve");
        for (C channel : reservedChannels) {
            grants.put(channel, new Grant(EXTERNAL_OWNER, Integer.MAX_VALUE));
        }
        requests.values().stream().sorted(requestOrder).forEach(this::resolveRequest);
        phase = Phase.RESOLVED;
    }

    public boolean owns(String owner, C channel) {
        String canonicalOwner = requireOwner(owner);
        Objects.requireNonNull(channel, "channel");
        if (phase != Phase.RESOLVED || blocked) {
            return false;
        }
        Grant grant = grants.get(channel);
        return grant != null && grant.owner().equals(canonicalOwner);
    }

    public boolean ownsAll(String owner, Set<C> channels) {
        String canonicalOwner = requireOwner(owner);
        Set<C> requested = copyChannels(channels, false);
        if (phase != Phase.RESOLVED || blocked) {
            return false;
        }
        return requested.stream().allMatch(channel -> owns(canonicalOwner, channel));
    }

    /** Returns this tick's decision for an owner, or {@code null} if none. */
    public Decision<C> decision(String owner) {
        return decisions.get(requireOwner(owner));
    }

    /**
     * Drops an owner's request and every grant it holds.
     *
     * <p>A release never promotes an already denied request: a service that
     * observed a denial must not become authorized later in the same tick.</p>
     */
    public void releaseOwner(String owner) {
        String canonicalOwner = requireOwner(owner);
        Request<C> request = requests.remove(canonicalOwner);
        grants.entrySet().removeIf(entry ->
                !EXTERNAL_OWNER.equals(entry.getValue().owner())
                        && entry.getValue().owner().equals(canonicalOwner)
        );
        Decision<C> previous = decisions.get(canonicalOwner);
        if (previous != null || request != null) {
            Decision<C> basis = previous != null ? previous : Decision.pending(request);
            decisions.put(canonicalOwner, basis.released());
        }
    }

    /** Cancels every pending and resolved action, keeping external reservations. */
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

    public long tick() {
        return tick;
    }

    public Phase phase() {
        return phase;
    }

    public int submittedCount() {
        return decisions.size();
    }

    public Map<C, Grant> grants() {
        EnumMap<C, Grant> copy = new EnumMap<>(channelType);
        copy.putAll(grants);
        return Collections.unmodifiableMap(copy);
    }

    public Map<String, Decision<C>> decisions() {
        return Collections.unmodifiableMap(new TreeMap<>(decisions));
    }

    public Set<C> reservedChannels() {
        return copyChannels(reservedChannels, true);
    }

    private void resolveRequest(Request<C> request) {
        Map<C, String> blockers = new EnumMap<>(channelType);
        for (C channel : request.channels()) {
            Grant grant = grants.get(channel);
            if (grant != null) {
                blockers.put(channel, grant.owner());
            }
        }
        if (!blockers.isEmpty()) {
            decisions.put(request.owner(), Decision.denied(request, blockers));
            return;
        }
        for (C channel : request.channels()) {
            grants.put(channel, new Grant(request.owner(), request.priority()));
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

    private String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException(label + " action owner cannot be blank");
        }
        String canonical = owner.trim();
        if (canonical.length() > MAXIMUM_OWNER_LENGTH) {
            throw new IllegalArgumentException(
                    label + " action owner cannot exceed "
                            + MAXIMUM_OWNER_LENGTH + " characters"
            );
        }
        return canonical;
    }

    private Set<C> copyChannels(Set<C> channels, boolean allowEmpty) {
        Objects.requireNonNull(channels, "channels");
        if (!allowEmpty && channels.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " action requires at least one channel"
            );
        }
        EnumSet<C> copy = EnumSet.noneOf(channelType);
        for (C channel : channels) {
            copy.add(Objects.requireNonNull(channel, "channel"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private record Request<C extends Enum<C>>(String owner, int priority, Set<C> channels) {
    }

    public enum Phase {
        IDLE,
        COLLECTING,
        RESOLVED
    }

    public enum Status {
        NOT_SUBMITTED,
        PENDING,
        GRANTED,
        DENIED,
        RELEASED,
        SAFETY_BLOCKED
    }

    public record Grant(String owner, int priority) {
        public Grant {
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("Action owner cannot be blank");
            }
            owner = owner.trim();
            if (owner.length() > MAXIMUM_OWNER_LENGTH) {
                throw new IllegalArgumentException(
                        "Action owner cannot exceed "
                                + MAXIMUM_OWNER_LENGTH + " characters"
                );
            }
        }
    }

    public record Decision<C extends Enum<C>>(
            Status status,
            int priority,
            Set<C> requestedChannels,
            Map<C, String> blockers
    ) {
        public Decision {
            status = Objects.requireNonNull(status, "status");
            requestedChannels = Set.copyOf(
                    Objects.requireNonNull(requestedChannels, "requestedChannels")
            );
            blockers = Map.copyOf(Objects.requireNonNull(blockers, "blockers"));
        }

        public boolean granted() {
            return status == Status.GRANTED;
        }

        private static <C extends Enum<C>> Decision<C> pending(Request<C> request) {
            return new Decision<>(
                    Status.PENDING, request.priority(), request.channels(), Map.of()
            );
        }

        private static <C extends Enum<C>> Decision<C> granted(Request<C> request) {
            return new Decision<>(
                    Status.GRANTED, request.priority(), request.channels(), Map.of()
            );
        }

        private static <C extends Enum<C>> Decision<C> denied(
                Request<C> request,
                Map<C, String> blockers
        ) {
            return new Decision<>(
                    Status.DENIED, request.priority(), request.channels(), blockers
            );
        }

        private Decision<C> released() {
            return new Decision<>(Status.RELEASED, priority, requestedChannels, Map.of());
        }
    }
}
