package dev.b2tclient.v26.utility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryManagerDecisionEngine26Test {
    @Test
    void selectsFirstLosslessMainInventoryMergeDeterministically() {
        List<InventoryManagerDecisionEngine26.Candidate> candidates =
                List.of(
                        candidate(15, 8, "stone"),
                        candidate(9, 40, "stone"),
                        candidate(12, 20, "stone"),
                        candidate(10, 16, "dirt")
                );

        InventoryManagerDecisionEngine26.Merge merge =
                InventoryManagerDecisionEngine26
                        .selectMerge(candidates)
                        .orElseThrow();

        assertEquals(12, merge.sourceSlot());
        assertEquals(9, merge.targetSlot());
        assertEquals(20, merge.sourceCount());
        assertEquals(40, merge.targetCount());
    }

    @Test
    void selectionRejectsHotbarPartialAndNonEquivalentMerges() {
        List<InventoryManagerDecisionEngine26.Candidate> candidates =
                List.of(
                        candidate(0, 8, "stone"),
                        candidate(9, 60, "stone"),
                        candidate(10, 8, "stone"),
                        candidate(11, 4, "dirt"),
                        new InventoryManagerDecisionEngine26.Candidate(
                                12,
                                4,
                                64,
                                false,
                                "stone"
                        )
                );

        assertTrue(
                InventoryManagerDecisionEngine26
                        .selectMerge(candidates)
                        .isEmpty()
        );
        assertTrue(
                InventoryManagerDecisionEngine26
                        .selectMerge(null)
                        .isEmpty()
        );
    }

    @Test
    void duplicateSlotsCannotReplaceTheFirstValidSnapshot() {
        List<InventoryManagerDecisionEngine26.Candidate> candidates =
                List.of(
                        candidate(9, 60, "stone"),
                        candidate(9, 1, "dirt"),
                        candidate(10, 4, "stone")
                );

        InventoryManagerDecisionEngine26.Merge merge =
                InventoryManagerDecisionEngine26
                        .selectMerge(candidates)
                        .orElseThrow();

        assertEquals("stone", merge.equivalenceGroup());
        assertEquals(4, merge.sourceCount());
    }

    @Test
    void sessionWarmupThenPreparesOneAtomicMerge() {
        InventoryManagerDecisionEngine26 engine =
                new InventoryManagerDecisionEngine26();
        var observation = readyObservation(7L);

        var warmup = engine.step(observation);
        var decision = engine.step(observation);

        assertEquals(
                InventoryManagerDecisionEngine26.BlockReason.SESSION_WARMUP,
                warmup.blockReason()
        );
        assertFalse(warmup.apply());
        assertEquals(
                InventoryManagerDecisionEngine26.Action.MERGE,
                decision.action()
        );
        assertTrue(decision.apply());
    }

    @Test
    void denialDoesNotAdvanceCooldownButSuccessDoes() {
        InventoryManagerDecisionEngine26 engine =
                new InventoryManagerDecisionEngine26(
                        new InventoryManagerDecisionEngine26.Timing(4, 3)
                );
        var observation = readyObservation(11L);
        engine.step(observation);

        var denied = engine.step(observation);
        engine.commit(denied, false);
        assertEquals(0, engine.snapshot().cooldownTicks());
        assertTrue(engine.step(observation).apply());

        var applied = engine.step(observation);
        engine.commit(applied, true);
        assertEquals(4, engine.snapshot().cooldownTicks());
        assertEquals(
                InventoryManagerDecisionEngine26.BlockReason.COOLDOWN,
                engine.step(observation).blockReason()
        );
        assertEquals(3, engine.snapshot().cooldownTicks());
    }

    @Test
    void onlyLatestOutstandingDecisionCanCommit() {
        InventoryManagerDecisionEngine26 engine =
                new InventoryManagerDecisionEngine26();
        var observation = readyObservation(13L);
        engine.step(observation);
        var stale = engine.step(observation);
        var latest = engine.step(observation);

        engine.commit(stale, true);
        assertEquals(0, engine.snapshot().cooldownTicks());
        engine.commit(latest, true);
        assertEquals(8, engine.snapshot().cooldownTicks());
    }

    @Test
    void manualYieldAndCooldownsAreStrictlyBounded() {
        InventoryManagerDecisionEngine26 engine =
                new InventoryManagerDecisionEngine26(
                        new InventoryManagerDecisionEngine26.Timing(40, 2)
                );
        engine.yieldToManualChange();
        assertEquals(2, engine.snapshot().cooldownTicks());
        engine.reset();
        assertEquals(0, engine.snapshot().cooldownTicks());

        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryManagerDecisionEngine26.Timing(1, 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryManagerDecisionEngine26.Timing(40, 41)
        );
    }

    @Test
    void resumedManualContextStartsTheFullQuietPeriod() {
        InventoryManagerDecisionEngine26 engine =
                new InventoryManagerDecisionEngine26(
                        new InventoryManagerDecisionEngine26.Timing(8, 4)
                );
        var ready = readyObservation(31L);
        engine.step(ready);
        var interruptedReturn =
                new InventoryManagerDecisionEngine26.Observation(
                        31L,
                        true,
                        true,
                        false,
                        false,
                        true,
                        List.of()
                );

        var decision = engine.step(interruptedReturn);

        assertEquals(
                InventoryManagerDecisionEngine26.BlockReason.MANUAL_CHANGE,
                decision.blockReason()
        );
        assertEquals(4, engine.snapshot().cooldownTicks());
        assertEquals(
                InventoryManagerDecisionEngine26.BlockReason.COOLDOWN,
                engine.step(ready).blockReason()
        );
        assertEquals(3, engine.snapshot().cooldownTicks());
    }

    @Test
    void invalidContextNeverProducesAnAction() {
        InventoryManagerDecisionEngine26 engine =
                new InventoryManagerDecisionEngine26();

        assertEquals(
                InventoryManagerDecisionEngine26.BlockReason.INVALID,
                engine.step(null).blockReason()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryManagerDecisionEngine26.Merge(
                        10,
                        9,
                        32,
                        40,
                        64,
                        "stone"
                )
        );
    }

    private static InventoryManagerDecisionEngine26.Observation
    readyObservation(long sessionKey) {
        return new InventoryManagerDecisionEngine26.Observation(
                sessionKey,
                true,
                true,
                true,
                true,
                false,
                List.of(
                        candidate(9, 40, "stone"),
                        candidate(10, 16, "stone")
                )
        );
    }

    private static InventoryManagerDecisionEngine26.Candidate candidate(
            int slot,
            int count,
            String group
    ) {
        return new InventoryManagerDecisionEngine26.Candidate(
                slot,
                count,
                64,
                true,
                group
        );
    }
}
