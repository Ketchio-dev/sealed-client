package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefensiveConstructionDecisionEngine26Test {
    @Test
    void choosesStableOrderBeforeDistanceForPatternedModules() {
        DefensiveConstructionDecisionEngine26.Candidate selected =
                DefensiveConstructionDecisionEngine26.selectBest(
                        DefensiveConstructionDecisionEngine26.Module.SURROUND,
                        List.of(
                                candidate(
                                        2L,
                                        DefensiveConstructionDecisionEngine26
                                                .Module.SURROUND,
                                        2,
                                        1.0,
                                        0.0
                                ),
                                candidate(
                                        1L,
                                        DefensiveConstructionDecisionEngine26
                                                .Module.SURROUND,
                                        0,
                                        9.0,
                                        0.0
                                )
                        ),
                        16,
                        25.0
                );

        assertEquals(1L, selected.key());
    }

    @Test
    void holeFillChoosesNearestEligibleSafeHole() {
        DefensiveConstructionDecisionEngine26.Candidate farther =
                candidate(
                        2L,
                        DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                        0,
                        4.0,
                        5.0
                );
        DefensiveConstructionDecisionEngine26.Candidate nearer =
                candidate(
                        1L,
                        DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                        0,
                        4.0,
                        2.0
                );

        assertEquals(
                1L,
                DefensiveConstructionDecisionEngine26.selectBest(
                        DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                        List.of(farther, nearer),
                        16,
                        25.0
                ).key()
        );
    }

    @Test
    void targetedModulesRejectFriendOrUnknownTargetObservation() {
        DefensiveConstructionDecisionEngine26.Candidate unsafe =
                new DefensiveConstructionDecisionEngine26.Candidate(
                        1L,
                        DefensiveConstructionDecisionEngine26.Module.AUTO_TRAP,
                        0,
                        2.0,
                        2.0,
                        true,
                        true,
                        true,
                        false,
                        true
                );
        assertNull(DefensiveConstructionDecisionEngine26.selectBest(
                DefensiveConstructionDecisionEngine26.Module.AUTO_TRAP,
                List.of(unsafe),
                4,
                25.0
        ));
    }

    @Test
    void holeFillRequiresBlastSafeHoleAndAllPlacementChecks() {
        DefensiveConstructionDecisionEngine26.Candidate notHole =
                new DefensiveConstructionDecisionEngine26.Candidate(
                        1L,
                        DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                        0,
                        2.0,
                        2.0,
                        true,
                        true,
                        true,
                        true,
                        false
                );
        DefensiveConstructionDecisionEngine26.Candidate unsupported =
                new DefensiveConstructionDecisionEngine26.Candidate(
                        2L,
                        DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                        0,
                        2.0,
                        2.0,
                        true,
                        false,
                        true,
                        true,
                        true
                );
        assertNull(DefensiveConstructionDecisionEngine26.selectBest(
                DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                List.of(notHole, unsupported),
                4,
                25.0
        ));
    }

    @Test
    void scanBudgetCannotBeBypassedByLaterCandidate() {
        List<DefensiveConstructionDecisionEngine26.Candidate> candidates =
                new ArrayList<>();
        candidates.add(new DefensiveConstructionDecisionEngine26.Candidate(
                0L,
                DefensiveConstructionDecisionEngine26.Module.SURROUND,
                0,
                1.0,
                0.0,
                false,
                true,
                true,
                true,
                true
        ));
        candidates.add(candidate(
                1L,
                DefensiveConstructionDecisionEngine26.Module.SURROUND,
                0,
                1.0,
                0.0
        ));

        assertNull(DefensiveConstructionDecisionEngine26.selectBest(
                DefensiveConstructionDecisionEngine26.Module.SURROUND,
                candidates,
                1,
                25.0
        ));
    }

    @Test
    void reachAndModuleIdentityFailClosed() {
        DefensiveConstructionDecisionEngine26.Candidate candidate =
                candidate(
                        1L,
                        DefensiveConstructionDecisionEngine26.Module.SELF_TRAP,
                        0,
                        26.0,
                        0.0
                );
        assertFalse(DefensiveConstructionDecisionEngine26.safe(
                DefensiveConstructionDecisionEngine26.Module.SELF_TRAP,
                candidate,
                25.0
        ));
        assertFalse(DefensiveConstructionDecisionEngine26.safe(
                DefensiveConstructionDecisionEngine26.Module.SURROUND,
                candidate,
                30.0
        ));
    }

    @Test
    void modeBoundLimitsKeepSimultaneousRangesIndependent() {
        DefensiveConstructionDecisionEngine26.Candidate surround =
                candidate(
                        1L,
                        DefensiveConstructionDecisionEngine26.Module.SURROUND,
                        0,
                        16.0,
                        0.0
                );
        DefensiveConstructionDecisionEngine26.Candidate hole =
                candidate(
                        2L,
                        DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                        0,
                        4.0,
                        1.0
                );
        var surroundLimits =
                new DefensiveConstructionDecisionEngine26.ModeLimits(
                        DefensiveConstructionDecisionEngine26.Module.SURROUND,
                        8,
                        9.0
                );
        var holeLimits =
                new DefensiveConstructionDecisionEngine26.ModeLimits(
                        DefensiveConstructionDecisionEngine26.Module.HOLE_FILL,
                        8,
                        9.0
                );

        assertNull(DefensiveConstructionDecisionEngine26.selectBest(
                List.of(surround, hole),
                surroundLimits
        ));
        assertEquals(
                2L,
                DefensiveConstructionDecisionEngine26.selectBest(
                        List.of(surround, hole),
                        holeLimits
                ).key()
        );
    }

    @Test
    void hotbarRestoreRequiresExactOwnedSelection() {
        assertEquals(
                2,
                DefensiveConstructionDecisionEngine26.restorationSlot(
                        2,
                        6,
                        6
                )
        );
        assertEquals(
                -1,
                DefensiveConstructionDecisionEngine26.restorationSlot(
                        2,
                        6,
                        4
                )
        );
        assertTrue(
                DefensiveConstructionDecisionEngine26.selectionWasReplaced(
                        2,
                        6,
                        4
                )
        );
        assertFalse(
                DefensiveConstructionDecisionEngine26.selectionWasReplaced(
                        2,
                        6,
                        6
                )
        );
    }

    @Test
    void malformedCandidatesAndLimitsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        -1L,
                        DefensiveConstructionDecisionEngine26.Module.SURROUND,
                        0,
                        1.0,
                        0.0
                )
        );
        assertNull(DefensiveConstructionDecisionEngine26.selectBest(
                DefensiveConstructionDecisionEngine26.Module.SURROUND,
                List.of(),
                0,
                25.0
        ));
        assertNull(DefensiveConstructionDecisionEngine26.selectBest(
                DefensiveConstructionDecisionEngine26.Module.SURROUND,
                List.of(),
                1,
                Double.NaN
        ));
    }

    @Test
    void nullCandidateInsideBudgetIsSkippedSafely() {
        List<DefensiveConstructionDecisionEngine26.Candidate> candidates =
                new ArrayList<>();
        candidates.add(null);
        candidates.add(candidate(
                1L,
                DefensiveConstructionDecisionEngine26.Module.SURROUND,
                0,
                1.0,
                0.0
        ));
        assertEquals(
                1L,
                DefensiveConstructionDecisionEngine26.selectBest(
                        DefensiveConstructionDecisionEngine26.Module.SURROUND,
                        candidates,
                        2,
                        25.0
                ).key()
        );
    }

    private static DefensiveConstructionDecisionEngine26.Candidate candidate(
            long key,
            DefensiveConstructionDecisionEngine26.Module module,
            int order,
            double selfDistanceSquared,
            double targetDistanceSquared
    ) {
        return new DefensiveConstructionDecisionEngine26.Candidate(
                key,
                module,
                order,
                selfDistanceSquared,
                targetDistanceSquared,
                true,
                true,
                true,
                true,
                true
        );
    }
}
