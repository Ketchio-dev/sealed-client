package dev.sealedclient.v26.visual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, bounded target selection for player ESP, tracers and nametags.
 *
 * <p>Runtime code owns entity discovery, interpolation and rendering. This
 * engine only accepts an explicit snapshot of relationship and visibility
 * state, then returns stable nearest-first selections. Each overlay has an
 * independent policy and render cap so an expensive feature cannot inherit
 * another feature's budget.</p>
 */
public final class EntityOverlayDecisionEngine26 {
    public static final int MAXIMUM_CANDIDATES = 512;
    public static final int MAXIMUM_RENDER_CAP = 256;
    public static final double MAXIMUM_DISTANCE = 1_024.0;

    private EntityOverlayDecisionEngine26() {
    }

    /**
     * Builds a deterministic render plan from at most the first 512 inputs.
     *
     * <p>Malformed candidates fail closed. Duplicate entity identifiers are
     * collapsed after sorting, retaining the nearest valid snapshot.</p>
     *
     * @param candidates entity snapshots in discovery order
     * @param configuration independent policies for all three overlays
     * @return immutable nearest-first render selections
     */
    public static OverlayPlan decide(
            List<Candidate> candidates,
            Configuration configuration
    ) {
        if (candidates == null || configuration == null) {
            return OverlayPlan.empty();
        }

        int examined = Math.min(candidates.size(), MAXIMUM_CANDIDATES);
        List<Candidate> ordered = candidates.stream()
                .limit(MAXIMUM_CANDIDATES)
                .filter(Objects::nonNull)
                .filter(Candidate::safe)
                .sorted(Comparator
                        .comparingDouble(Candidate::distanceSquared)
                        .thenComparingInt(Candidate::entityId))
                .toList();

        List<Selection> playerEsp = new ArrayList<>();
        List<Selection> tracers = new ArrayList<>();
        List<Selection> nametags = new ArrayList<>();
        Set<Integer> seenEntityIds = new HashSet<>();
        int eligible = 0;

        for (Candidate candidate : ordered) {
            if (!seenEntityIds.add(candidate.entityId())) {
                continue;
            }

            boolean selected = appendIfEligible(
                    candidate,
                    configuration.playerEsp(),
                    playerEsp
            );
            selected |= appendIfEligible(
                    candidate,
                    configuration.tracers(),
                    tracers
            );
            selected |= appendIfEligible(
                    candidate,
                    configuration.nametags(),
                    nametags
            );
            if (selected) {
                eligible++;
            }
        }

        return new OverlayPlan(
                playerEsp,
                tracers,
                nametags,
                examined,
                eligible
        );
    }

    private static boolean appendIfEligible(
            Candidate candidate,
            OverlayPolicy policy,
            List<Selection> output
    ) {
        if (output.size() >= policy.renderCap()
                || !policy.accepts(candidate)) {
            return false;
        }
        output.add(Selection.from(candidate));
        return true;
    }

    /**
     * Explicit immutable entity snapshot supplied by the render runtime.
     */
    public record Candidate(
            int entityId,
            double distanceSquared,
            boolean friend,
            boolean self,
            boolean invisible,
            boolean alive,
            boolean spectator,
            boolean inFrustum,
            boolean lineOfSight
    ) {
        boolean safe() {
            return entityId >= 0
                    && Double.isFinite(distanceSquared)
                    && distanceSquared >= 0.0
                    && alive
                    && !spectator;
        }
    }

    /**
     * Per-overlay relation, visibility, range and work-budget policy.
     */
    public record OverlayPolicy(
            boolean enabled,
            double maximumDistance,
            int renderCap,
            boolean includeFriends,
            boolean includeSelf,
            boolean includeInvisible,
            boolean requireInFrustum,
            boolean requireLineOfSight
    ) {
        public OverlayPolicy {
            if (!Double.isFinite(maximumDistance)
                    || maximumDistance <= 0.0
                    || maximumDistance > MAXIMUM_DISTANCE) {
                throw new IllegalArgumentException(
                        "maximumDistance must be in (0, "
                                + MAXIMUM_DISTANCE
                                + "]"
                );
            }
            if (renderCap < 0 || renderCap > MAXIMUM_RENDER_CAP) {
                throw new IllegalArgumentException(
                        "renderCap must be in [0, "
                                + MAXIMUM_RENDER_CAP
                                + "]"
                );
            }
        }

        boolean accepts(Candidate candidate) {
            if (!enabled || renderCap == 0) {
                return false;
            }
            if (candidate.distanceSquared()
                    > maximumDistance * maximumDistance) {
                return false;
            }
            if (candidate.self()) {
                if (!includeSelf) {
                    return false;
                }
            } else if (candidate.friend() && !includeFriends) {
                return false;
            }
            return (includeInvisible || !candidate.invisible())
                    && (!requireInFrustum || candidate.inFrustum())
                    && (!requireLineOfSight || candidate.lineOfSight());
        }
    }

    public record Configuration(
            OverlayPolicy playerEsp,
            OverlayPolicy tracers,
            OverlayPolicy nametags
    ) {
        public Configuration {
            Objects.requireNonNull(playerEsp, "playerEsp");
            Objects.requireNonNull(tracers, "tracers");
            Objects.requireNonNull(nametags, "nametags");
        }
    }

    /**
     * Minimum render-facing data retained after filtering.
     */
    public record Selection(
            int entityId,
            double distanceSquared,
            boolean friend,
            boolean self,
            boolean inFrustum,
            boolean lineOfSight
    ) {
        static Selection from(Candidate candidate) {
            return new Selection(
                    candidate.entityId(),
                    candidate.distanceSquared(),
                    candidate.friend(),
                    candidate.self(),
                    candidate.inFrustum(),
                    candidate.lineOfSight()
            );
        }
    }

    public record OverlayPlan(
            List<Selection> playerEspTargets,
            List<Selection> tracerTargets,
            List<Selection> nametagTargets,
            int candidatesExamined,
            int eligibleEntities
    ) {
        public OverlayPlan {
            playerEspTargets = List.copyOf(
                    Objects.requireNonNull(
                            playerEspTargets,
                            "playerEspTargets"
                    )
            );
            tracerTargets = List.copyOf(
                    Objects.requireNonNull(tracerTargets, "tracerTargets")
            );
            nametagTargets = List.copyOf(
                    Objects.requireNonNull(
                            nametagTargets,
                            "nametagTargets"
                    )
            );
            if (candidatesExamined < 0
                    || candidatesExamined > MAXIMUM_CANDIDATES) {
                throw new IllegalArgumentException(
                        "candidatesExamined is outside the bounded range"
                );
            }
            if (eligibleEntities < 0
                    || eligibleEntities > candidatesExamined) {
                throw new IllegalArgumentException(
                        "eligibleEntities is outside the examined range"
                );
            }
        }

        public static OverlayPlan empty() {
            return new OverlayPlan(
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    0
            );
        }
    }
}
