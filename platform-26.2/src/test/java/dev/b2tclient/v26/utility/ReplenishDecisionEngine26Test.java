package dev.b2tclient.v26.utility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplenishDecisionEngine26Test {
    private static final ReplenishDecisionEngine26.Timing TIMING =
            new ReplenishDecisionEngine26.Timing(4, 2);

    @Test
    void selectsFirstEligibleTargetThenLargestLowestSlotSource() {
        List<ReplenishDecisionEngine26.Candidate> candidates = List.of(
                candidate(3, 9, 8, 40),
                candidate(1, 12, 16, 32),
                candidate(1, 11, 16, 48),
                candidate(1, 10, 16, 48),
                candidate(0, 9, 17, 64)
        );

        ReplenishDecisionEngine26.Candidate selected =
                ReplenishDecisionEngine26.selectCandidate(
                        candidates,
                        16
                ).orElseThrow();

        assertEquals(1, selected.hotbarSlot());
        assertEquals(10, selected.sourceInventorySlot());
        assertEquals(48, selected.sourceCount());
    }

    @Test
    void rejectsMalformedFullUnstackableAndInexactPairs() {
        List<ReplenishDecisionEngine26.Candidate> candidates = List.of(
                new ReplenishDecisionEngine26.Candidate(
                        0, 9, 64, 64, 64, true, true
                ),
                new ReplenishDecisionEngine26.Candidate(
                        1, 10, 8, 64, 64, false, true
                ),
                new ReplenishDecisionEngine26.Candidate(
                        2, 11, 8, 64, 64, true, false
                ),
                new ReplenishDecisionEngine26.Candidate(
                        9, 12, 8, 64, 64, true, true
                ),
                new ReplenishDecisionEngine26.Candidate(
                        3, 8, 8, 64, 64, true, true
                )
        );

        assertTrue(
                ReplenishDecisionEngine26.selectCandidate(
                        candidates,
                        16
                ).isEmpty()
        );
        assertTrue(
                ReplenishDecisionEngine26.selectCandidate(
                        null,
                        16
                ).isEmpty()
        );
        assertTrue(
                ReplenishDecisionEngine26.selectCandidate(
                        candidates,
                        0
                ).isEmpty()
        );
    }

    @Test
    void createsExactMenuMappingAndNonOverflowPostcondition() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(10L, 20L, false, false));

        ReplenishDecisionEngine26.Decision decision =
                engine.step(observation(10L, 21L, false, false));

        assertTrue(decision.apply());
        assertEquals(12, decision.sourceInventorySlot());
        assertEquals(12, decision.sourceMenuSlot());
        assertEquals(2, decision.targetHotbarSlot());
        assertEquals(38, decision.targetMenuSlot());
        assertEquals(32, decision.sourceCountBefore());
        assertEquals(16, decision.targetCountBefore());
        assertEquals(0, decision.sourceCountAfter());
        assertEquals(48, decision.targetCountAfter());
    }

    @Test
    void calculatesOverflowRemainderExactly() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        ReplenishDecisionEngine26.Observation overflow =
                new ReplenishDecisionEngine26.Observation(
                        11L,
                        30L,
                        true,
                        true,
                        true,
                        false,
                        false,
                        63,
                        List.of(candidate(4, 20, 50, 32))
                );
        engine.step(overflow);

        ReplenishDecisionEngine26.Decision decision =
                engine.step(new ReplenishDecisionEngine26.Observation(
                        11L,
                        31L,
                        true,
                        true,
                        true,
                        false,
                        false,
                        63,
                        List.of(candidate(4, 20, 50, 32))
                ));

        assertEquals(18, decision.sourceCountAfter());
        assertEquals(64, decision.targetCountAfter());
    }

    @Test
    void recoveryClassifierAcceptsOnlyOwnedExactCursorStages() {
        assertTrue(
                ReplenishDecisionEngine26.ownedRecoveryCandidate(
                        32,
                        18,
                        32,
                        true
                )
        );
        assertTrue(
                ReplenishDecisionEngine26.ownedRecoveryCandidate(
                        32,
                        18,
                        18,
                        true
                )
        );
        assertFalse(
                ReplenishDecisionEngine26.ownedRecoveryCandidate(
                        32,
                        18,
                        24,
                        true
                )
        );
        assertFalse(
                ReplenishDecisionEngine26.ownedRecoveryCandidate(
                        32,
                        18,
                        18,
                        false
                )
        );
        assertFalse(
                ReplenishDecisionEngine26.ownedRecoveryCandidate(
                        32,
                        0,
                        1,
                        true
                )
        );
        assertTrue(
                ReplenishDecisionEngine26.ownedRecoveryCandidate(
                        32,
                        0,
                        32,
                        true
                )
        );
    }

    @Test
    void deniedClaimDoesNotConsumeTickBudgetOrCooldown() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(12L, 40L, false, false));
        ReplenishDecisionEngine26.Decision first =
                engine.step(observation(12L, 41L, false, false));

        engine.commit(
                first,
                ReplenishDecisionEngine26.CommitResult.DENIED
        );
        ReplenishDecisionEngine26.Decision retry =
                engine.step(observation(12L, 41L, false, false));

        assertTrue(retry.apply());
        assertEquals(0, engine.snapshot().cooldownTicks());
    }

    @Test
    void appliedOperationAllowsOnlyOneLogicalTransactionPerTick() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(13L, 50L, false, false));
        ReplenishDecisionEngine26.Decision first =
                engine.step(observation(13L, 51L, false, false));
        engine.commit(
                first,
                ReplenishDecisionEngine26.CommitResult.APPLIED
        );

        ReplenishDecisionEngine26.Decision sameTick =
                engine.step(observation(13L, 51L, false, false));

        assertFalse(sameTick.apply());
        assertEquals(
                ReplenishDecisionEngine26.BlockReason.OPERATION_BUDGET,
                sameTick.blockReason()
        );
    }

    @Test
    void successAndFailureCooldownsAreBoundedAndExpire() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(14L, 60L, false, false));
        ReplenishDecisionEngine26.Decision first =
                engine.step(observation(14L, 61L, false, false));
        engine.commit(
                first,
                ReplenishDecisionEngine26.CommitResult.APPLIED
        );
        assertEquals(4, engine.snapshot().cooldownTicks());

        for (long tick = 62L; tick < 65L; tick++) {
            assertEquals(
                    ReplenishDecisionEngine26.BlockReason.COOLDOWN,
                    engine.step(
                            observation(14L, tick, false, false)
                    ).blockReason()
            );
        }
        assertTrue(
                engine.step(
                        observation(14L, 65L, false, false)
                ).apply()
        );

        ReplenishDecisionEngine26.Decision failed =
                engine.step(observation(14L, 66L, false, false));
        engine.commit(
                failed,
                ReplenishDecisionEngine26.CommitResult
                        .FAILED_AFTER_OPERATION
        );
        assertEquals(2, engine.snapshot().cooldownTicks());
    }

    @Test
    void utilityHotbarOwnershipPreventsPlanningAndClaiming() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(15L, 70L, false, false));

        ReplenishDecisionEngine26.Decision blocked =
                engine.step(observation(15L, 71L, true, false));

        assertFalse(blocked.apply());
        assertEquals(
                ReplenishDecisionEngine26.BlockReason.UTILITY_CONFLICT,
                blocked.blockReason()
        );
    }

    @Test
    void manualInventoryChangeStartsBoundedQuietPeriod() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(151L, 70L, false, false));

        ReplenishDecisionEngine26.Decision manual =
                engine.step(new ReplenishDecisionEngine26.Observation(
                        151L,
                        71L,
                        true,
                        true,
                        true,
                        true,
                        false,
                        16,
                        List.of(candidate(2, 12, 16, 32))
                ));

        assertEquals(
                ReplenishDecisionEngine26.BlockReason.MANUAL_CHANGE,
                manual.blockReason()
        );
        assertEquals(2, engine.snapshot().cooldownTicks());
        assertEquals(
                ReplenishDecisionEngine26.BlockReason.COOLDOWN,
                engine.step(
                        observation(151L, 72L, false, false)
                ).blockReason()
        );
        assertTrue(
                engine.step(
                        observation(151L, 73L, false, false)
                ).apply()
        );
    }

    @Test
    void preparedInvalidationYieldsWithoutConsumingOperationBudget() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(152L, 74L, false, false));
        ReplenishDecisionEngine26.Decision decision =
                engine.step(observation(152L, 75L, false, false));

        engine.commit(
                decision,
                ReplenishDecisionEngine26.CommitResult.INVALIDATED
        );

        assertEquals(2, engine.snapshot().cooldownTicks());
        assertEquals(
                Long.MIN_VALUE,
                engine.snapshot().lastOperationTick()
        );
    }

    @Test
    void sessionChangeClearsBudgetCooldownAndRequiresWarmup() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(16L, 80L, false, false));
        ReplenishDecisionEngine26.Decision action =
                engine.step(observation(16L, 81L, false, false));
        engine.commit(
                action,
                ReplenishDecisionEngine26.CommitResult.APPLIED
        );

        ReplenishDecisionEngine26.Decision changed =
                engine.step(observation(17L, 0L, false, false));

        assertEquals(
                ReplenishDecisionEngine26.BlockReason.SESSION_WARMUP,
                changed.blockReason()
        );
        assertEquals(0, engine.snapshot().cooldownTicks());
        assertEquals(
                Long.MIN_VALUE,
                engine.snapshot().lastOperationTick()
        );
        assertTrue(
                engine.step(
                        observation(17L, 1L, false, false)
                ).apply()
        );
    }

    @Test
    void unavailableSessionAndManualInventoryStateFailClosed() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);

        ReplenishDecisionEngine26.Decision noSession =
                engine.step(new ReplenishDecisionEngine26.Observation(
                        18L,
                        0L,
                        true,
                        false,
                        false,
                        false,
                        false,
                        16,
                        List.of(candidate(2, 12, 16, 32))
                ));
        assertEquals(
                ReplenishDecisionEngine26.BlockReason.SESSION,
                noSession.blockReason()
        );

        engine.step(observation(18L, 1L, false, false));
        ReplenishDecisionEngine26.Decision cursorBusy =
                engine.step(observation(18L, 2L, false, true));
        assertEquals(
                ReplenishDecisionEngine26.BlockReason.INVENTORY,
                cursorBusy.blockReason()
        );
    }

    @Test
    void staleCommitCannotAffectLatestDecision() {
        ReplenishDecisionEngine26 engine =
                new ReplenishDecisionEngine26(TIMING);
        engine.step(observation(19L, 90L, false, false));
        ReplenishDecisionEngine26.Decision stale =
                engine.step(observation(19L, 91L, false, false));
        ReplenishDecisionEngine26.Decision latest =
                engine.step(observation(19L, 92L, false, false));

        engine.commit(
                stale,
                ReplenishDecisionEngine26.CommitResult.APPLIED
        );

        assertEquals(0, engine.snapshot().cooldownTicks());
        assertEquals(latest, engine.snapshot().outstanding());
    }

    @Test
    void validatesTimingObservationCandidateBoundsAndMappings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplenishDecisionEngine26.Timing(0, 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplenishDecisionEngine26.Timing(4, 21)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ReplenishDecisionEngine26
                        .inventoryIndexToMenuSlot(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ReplenishDecisionEngine26
                        .inventoryIndexToMenuSlot(36)
        );
        assertEquals(
                36,
                ReplenishDecisionEngine26.inventoryIndexToMenuSlot(0)
        );
        assertEquals(
                44,
                ReplenishDecisionEngine26.inventoryIndexToMenuSlot(8)
        );
        assertEquals(
                9,
                ReplenishDecisionEngine26.inventoryIndexToMenuSlot(9)
        );

        List<ReplenishDecisionEngine26.Candidate> tooMany =
                new ArrayList<>();
        for (int index = 0; index < 244; index++) {
            tooMany.add(candidate(0, 9, 1, 1));
        }
        assertTrue(
                ReplenishDecisionEngine26.selectCandidate(
                        tooMany,
                        16
                ).isEmpty()
        );
    }

    private static ReplenishDecisionEngine26.Observation observation(
            long session,
            long tick,
            boolean utilityOwned,
            boolean inventoryBusy
    ) {
        return new ReplenishDecisionEngine26.Observation(
                session,
                tick,
                true,
                true,
                !inventoryBusy,
                false,
                utilityOwned,
                16,
                List.of(candidate(2, 12, 16, 32))
        );
    }

    private static ReplenishDecisionEngine26.Candidate candidate(
            int hotbarSlot,
            int sourceSlot,
            int targetCount,
            int sourceCount
    ) {
        return new ReplenishDecisionEngine26.Candidate(
                hotbarSlot,
                sourceSlot,
                targetCount,
                sourceCount,
                64,
                true,
                true
        );
    }
}
