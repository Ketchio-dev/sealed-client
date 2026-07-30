package dev.sealedclient.v26.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOverlayDecisionEngine26Test {
    private static final EntityOverlayDecisionEngine26.OverlayPolicy
            DISABLED = policy(false, 64.0, 0);

    @Test
    void appliesIndependentNearestFirstRenderCaps() {
        EntityOverlayDecisionEngine26.Configuration configuration =
                new EntityOverlayDecisionEngine26.Configuration(
                        policy(true, 64.0, 2),
                        policy(true, 64.0, 3),
                        policy(true, 64.0, 1)
                );

        EntityOverlayDecisionEngine26.OverlayPlan plan =
                EntityOverlayDecisionEngine26.decide(
                        List.of(
                                candidate(30, 9.0),
                                candidate(20, 4.0),
                                candidate(10, 4.0),
                                candidate(40, 16.0)
                        ),
                        configuration
                );

        assertEquals(List.of(10, 20), ids(plan.playerEspTargets()));
        assertEquals(List.of(10, 20, 30), ids(plan.tracerTargets()));
        assertEquals(List.of(10), ids(plan.nametagTargets()));
        assertEquals(4, plan.candidatesExamined());
        assertEquals(3, plan.eligibleEntities());
    }

    @Test
    void eachOverlayAppliesItsOwnRelationAndVisibilityPolicy() {
        EntityOverlayDecisionEngine26.OverlayPolicy esp =
                new EntityOverlayDecisionEngine26.OverlayPolicy(
                        true,
                        32.0,
                        16,
                        true,
                        false,
                        true,
                        true,
                        false
                );
        EntityOverlayDecisionEngine26.OverlayPolicy tracers =
                new EntityOverlayDecisionEngine26.OverlayPolicy(
                        true,
                        64.0,
                        16,
                        false,
                        false,
                        false,
                        false,
                        false
                );
        EntityOverlayDecisionEngine26.OverlayPolicy nametags =
                new EntityOverlayDecisionEngine26.OverlayPolicy(
                        true,
                        48.0,
                        16,
                        false,
                        false,
                        false,
                        true,
                        true
                );

        EntityOverlayDecisionEngine26.OverlayPlan plan =
                EntityOverlayDecisionEngine26.decide(
                        List.of(
                                candidate(
                                        1,
                                        100.0,
                                        true,
                                        false,
                                        false,
                                        true,
                                        true
                                ),
                                candidate(
                                        2,
                                        121.0,
                                        false,
                                        true,
                                        false,
                                        true,
                                        true
                                ),
                                candidate(
                                        3,
                                        144.0,
                                        false,
                                        false,
                                        true,
                                        true,
                                        true
                                ),
                                candidate(
                                        4,
                                        169.0,
                                        false,
                                        false,
                                        false,
                                        false,
                                        true
                                ),
                                candidate(
                                        5,
                                        196.0,
                                        false,
                                        false,
                                        false,
                                        true,
                                        false
                                ),
                                candidate(6, 40.0 * 40.0),
                                candidate(7, 70.0 * 70.0)
                        ),
                        new EntityOverlayDecisionEngine26.Configuration(
                                esp,
                                tracers,
                                nametags
                        )
                );

        assertEquals(List.of(1, 3, 5), ids(plan.playerEspTargets()));
        assertEquals(List.of(4, 5, 6), ids(plan.tracerTargets()));
        assertEquals(List.of(6), ids(plan.nametagTargets()));
        assertEquals(5, plan.eligibleEntities());
    }

    @Test
    void includeSelfIsIndependentFromFriendFiltering() {
        EntityOverlayDecisionEngine26.OverlayPolicy selfOnly =
                new EntityOverlayDecisionEngine26.OverlayPolicy(
                        true,
                        32.0,
                        4,
                        false,
                        true,
                        false,
                        true,
                        true
                );
        EntityOverlayDecisionEngine26.Candidate selfMarkedAsFriend =
                candidate(
                        1,
                        0.0,
                        true,
                        true,
                        false,
                        true,
                        true
                );
        EntityOverlayDecisionEngine26.Candidate ordinaryFriend =
                candidate(
                        2,
                        1.0,
                        true,
                        false,
                        false,
                        true,
                        true
                );

        EntityOverlayDecisionEngine26.OverlayPlan plan =
                EntityOverlayDecisionEngine26.decide(
                        List.of(selfMarkedAsFriend, ordinaryFriend),
                        new EntityOverlayDecisionEngine26.Configuration(
                                selfOnly,
                                DISABLED,
                                DISABLED
                        )
                );

        assertEquals(List.of(1), ids(plan.playerEspTargets()));
    }

    @Test
    void rejectsMalformedDeadAndSpectatorCandidates() {
        List<EntityOverlayDecisionEngine26.Candidate> candidates =
                new ArrayList<>();
        candidates.add(null);
        candidates.add(new EntityOverlayDecisionEngine26.Candidate(
                -1,
                1.0,
                false,
                false,
                false,
                true,
                false,
                true,
                true
        ));
        candidates.add(new EntityOverlayDecisionEngine26.Candidate(
                1,
                Double.NaN,
                false,
                false,
                false,
                true,
                false,
                true,
                true
        ));
        candidates.add(new EntityOverlayDecisionEngine26.Candidate(
                2,
                -1.0,
                false,
                false,
                false,
                true,
                false,
                true,
                true
        ));
        candidates.add(new EntityOverlayDecisionEngine26.Candidate(
                3,
                1.0,
                false,
                false,
                false,
                false,
                false,
                true,
                true
        ));
        candidates.add(new EntityOverlayDecisionEngine26.Candidate(
                4,
                1.0,
                false,
                false,
                false,
                true,
                true,
                true,
                true
        ));

        EntityOverlayDecisionEngine26.OverlayPlan plan =
                EntityOverlayDecisionEngine26.decide(
                        candidates,
                        new EntityOverlayDecisionEngine26.Configuration(
                                policy(true, 32.0, 16),
                                policy(true, 32.0, 16),
                                policy(true, 32.0, 16)
                        )
                );

        assertTrue(plan.playerEspTargets().isEmpty());
        assertTrue(plan.tracerTargets().isEmpty());
        assertTrue(plan.nametagTargets().isEmpty());
        assertEquals(6, plan.candidatesExamined());
        assertEquals(0, plan.eligibleEntities());
    }

    @Test
    void candidateWorkAndAllOutputListsAreHardCapped() {
        List<EntityOverlayDecisionEngine26.Candidate> candidates =
                new ArrayList<>();
        for (int index = 0;
             index < EntityOverlayDecisionEngine26.MAXIMUM_CANDIDATES;
             index++) {
            candidates.add(candidate(index, 100.0 + index));
        }
        candidates.add(candidate(10_000, 1.0));

        EntityOverlayDecisionEngine26.OverlayPolicy maximum =
                policy(
                        true,
                        EntityOverlayDecisionEngine26.MAXIMUM_DISTANCE,
                        EntityOverlayDecisionEngine26.MAXIMUM_RENDER_CAP
                );
        EntityOverlayDecisionEngine26.OverlayPlan plan =
                EntityOverlayDecisionEngine26.decide(
                        candidates,
                        new EntityOverlayDecisionEngine26.Configuration(
                                maximum,
                                maximum,
                                maximum
                        )
                );

        assertEquals(
                EntityOverlayDecisionEngine26.MAXIMUM_CANDIDATES,
                plan.candidatesExamined()
        );
        assertEquals(
                EntityOverlayDecisionEngine26.MAXIMUM_RENDER_CAP,
                plan.playerEspTargets().size()
        );
        assertEquals(
                EntityOverlayDecisionEngine26.MAXIMUM_RENDER_CAP,
                plan.tracerTargets().size()
        );
        assertEquals(
                EntityOverlayDecisionEngine26.MAXIMUM_RENDER_CAP,
                plan.nametagTargets().size()
        );
        assertTrue(ids(plan.playerEspTargets()).stream()
                .noneMatch(id -> id == 10_000));
    }

    @Test
    void duplicateEntityIdKeepsNearestSnapshotOnly() {
        EntityOverlayDecisionEngine26.OverlayPlan plan =
                EntityOverlayDecisionEngine26.decide(
                        List.of(
                                candidate(7, 100.0),
                                candidate(7, 4.0),
                                candidate(8, 9.0)
                        ),
                        new EntityOverlayDecisionEngine26.Configuration(
                                policy(true, 64.0, 16),
                                DISABLED,
                                DISABLED
                        )
                );

        assertEquals(List.of(7, 8), ids(plan.playerEspTargets()));
        assertEquals(
                4.0,
                plan.playerEspTargets().getFirst().distanceSquared()
        );
        assertEquals(2, plan.eligibleEntities());
    }

    @Test
    void plansAreImmutableAndNullInputsFailClosed() {
        EntityOverlayDecisionEngine26.OverlayPlan emptyFromCandidates =
                EntityOverlayDecisionEngine26.decide(null, null);
        assertTrue(emptyFromCandidates.playerEspTargets().isEmpty());

        EntityOverlayDecisionEngine26.OverlayPlan plan =
                EntityOverlayDecisionEngine26.decide(
                        List.of(candidate(1, 1.0)),
                        new EntityOverlayDecisionEngine26.Configuration(
                                policy(true, 16.0, 1),
                                DISABLED,
                                DISABLED
                        )
                );
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.playerEspTargets().clear()
        );
    }

    @Test
    void policyRejectsUnboundedDistanceAndRenderCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(true, 0.0, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(true, Double.NaN, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(
                        true,
                        EntityOverlayDecisionEngine26.MAXIMUM_DISTANCE + 1.0,
                        1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(true, 64.0, -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(
                        true,
                        64.0,
                        EntityOverlayDecisionEngine26.MAXIMUM_RENDER_CAP + 1
                )
        );
    }

    private static List<Integer> ids(
            List<EntityOverlayDecisionEngine26.Selection> selections
    ) {
        return selections.stream()
                .map(EntityOverlayDecisionEngine26.Selection::entityId)
                .toList();
    }

    private static EntityOverlayDecisionEngine26.OverlayPolicy policy(
            boolean enabled,
            double maximumDistance,
            int renderCap
    ) {
        return new EntityOverlayDecisionEngine26.OverlayPolicy(
                enabled,
                maximumDistance,
                renderCap,
                false,
                false,
                false,
                true,
                true
        );
    }

    private static EntityOverlayDecisionEngine26.Candidate candidate(
            int entityId,
            double distanceSquared
    ) {
        return candidate(
                entityId,
                distanceSquared,
                false,
                false,
                false,
                true,
                true
        );
    }

    private static EntityOverlayDecisionEngine26.Candidate candidate(
            int entityId,
            double distanceSquared,
            boolean friend,
            boolean self,
            boolean invisible,
            boolean inFrustum,
            boolean lineOfSight
    ) {
        return new EntityOverlayDecisionEngine26.Candidate(
                entityId,
                distanceSquared,
                friend,
                self,
                invisible,
                true,
                false,
                inFrustum,
                lineOfSight
        );
    }
}
