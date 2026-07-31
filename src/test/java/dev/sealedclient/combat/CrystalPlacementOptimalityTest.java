package dev.sealedclient.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that crystal selection actually picks the best available option.
 *
 * <p>Selecting the highest-scoring candidate is the whole point of scoring
 * them, but the production loop keeps a single running best rather than sorting,
 * so a comparison mistake would quietly cost damage without failing anything.
 * These tests re-derive the optimum by exhaustive search over the same candidate
 * set and require the greedy scan to agree.</p>
 *
 * <p>Exhaustive comparison is only tractable on small sets, so the scenarios
 * here are bounded. This proves the selection rule is correct, not that a live
 * game with hundreds of candidates is searched exhaustively.</p>
 */
class CrystalPlacementOptimalityTest {
    private static final double SELF_WEIGHT = 1.5;
    private static final double MIN_DAMAGE = 6.0;
    private static final double MAX_SELF_DAMAGE = 8.0;
    private static final double SELF_HEALTH = 20.0;

    private record Candidate(
            String name,
            double targetDamage,
            double selfDamage,
            double targetDistance,
            double actionDistance,
            boolean facePlace
    ) {
        double score() {
            return CrystalScoring.score(
                    targetDamage, selfDamage, targetDistance, actionDistance, SELF_WEIGHT
            );
        }

        boolean acceptable() {
            return CrystalScoring.acceptable(
                    targetDamage, selfDamage, SELF_HEALTH, MIN_DAMAGE, MAX_SELF_DAMAGE, facePlace
            );
        }
    }

    /** The production selection rule: one pass, keeping the running best. */
    private static Candidate greedyBest(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate.acceptable()
                    && (best == null || candidate.score() > best.score())) {
                best = candidate;
            }
        }
        return best;
    }

    /** The optimum, found by looking at every acceptable candidate. */
    private static Candidate exhaustiveBest(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (!candidate.acceptable()) {
                continue;
            }
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    @Test
    void theGreedyScanMatchesExhaustiveSearchOnRandomCandidateSets() {
        Random random = new Random(20260731L);

        for (int trial = 0; trial < 2000; trial++) {
            int count = 1 + random.nextInt(64);
            List<Candidate> candidates = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                candidates.add(new Candidate(
                        "c" + i,
                        random.nextDouble() * 24.0,
                        random.nextDouble() * 12.0,
                        random.nextDouble() * 8.0,
                        random.nextDouble() * 6.0,
                        random.nextInt(4) == 0
                ));
            }

            Candidate greedy = greedyBest(candidates);
            Candidate optimal = exhaustiveBest(candidates);

            if (optimal == null) {
                assertEquals(null, greedy,
                        "trial " + trial + ": nothing was acceptable, so nothing may be chosen");
                continue;
            }
            assertTrue(greedy != null, "trial " + trial + ": an acceptable candidate was missed");
            assertEquals(
                    optimal.score(),
                    greedy.score(),
                    1.0e-9,
                    "trial " + trial + ": chose " + greedy.name()
                            + " scoring " + greedy.score()
                            + " when " + optimal.name()
                            + " scored " + optimal.score()
            );
        }
    }

    @Test
    void candidateOrderCannotChangeTheChosenScore() {
        Random random = new Random(4242L);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            candidates.add(new Candidate(
                    "c" + i,
                    random.nextDouble() * 20.0,
                    random.nextDouble() * 6.0,
                    random.nextDouble() * 8.0,
                    random.nextDouble() * 4.0,
                    false
            ));
        }

        double expected = exhaustiveBest(candidates).score();
        for (int shuffle = 0; shuffle < 200; shuffle++) {
            List<Candidate> shuffled = new ArrayList<>(candidates);
            java.util.Collections.shuffle(shuffled, random);
            assertEquals(expected, greedyBest(shuffled).score(), 1.0e-9);
        }
    }

    @Test
    void aCandidateThatWouldKillTheLocalPlayerIsNeverChosen() {
        // The lethal option scores highest on damage alone, so if the safety
        // rule were ever dropped this is the one that would be taken.
        List<Candidate> candidates = List.of(
                new Candidate("lethal", 40.0, SELF_HEALTH, 1.0, 1.0, false),
                new Candidate("safe", 9.0, 1.0, 2.0, 2.0, false)
        );

        Candidate chosen = greedyBest(candidates);
        assertEquals("safe", chosen.name());
        assertEquals(chosen.score(), exhaustiveBest(candidates).score(), 1.0e-9);
    }

    @Test
    void weakHitsAreSkippedUnlessTheyAreFacePlaces() {
        Candidate weak = new Candidate("weak", MIN_DAMAGE - 1.0, 0.5, 1.0, 1.0, false);
        Candidate weakFacePlace = new Candidate("face", MIN_DAMAGE - 1.0, 0.5, 1.0, 1.0, true);

        assertEquals(null, greedyBest(List.of(weak)));
        assertEquals("face", greedyBest(List.of(weakFacePlace)).name());
    }

    @Test
    void selfDamageIsWeightedAgainstTargetDamageRatherThanIgnored() {
        // Equal damage to the target, but one hurts far more. The scoring must
        // prefer the cheaper option rather than treating them as equivalent.
        List<Candidate> candidates = List.of(
                new Candidate("costly", 12.0, 7.0, 1.0, 1.0, false),
                new Candidate("cheap", 12.0, 0.5, 1.0, 1.0, false)
        );
        assertEquals("cheap", greedyBest(candidates).name());
    }
}
