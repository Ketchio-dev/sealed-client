package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestSwapDecisionEngine26Test {
    private static final ChestSwapDecisionEngine26.Timing TIMING =
            new ChestSwapDecisionEngine26.Timing(3, 5, 3);

    @Test
    void selectsHealthyElytraWithoutTouchingSelectedHotbar() {
        ChestSwapDecisionEngine26.Candidate selected =
                ChestSwapDecisionEngine26.selectCandidate(
                        false,
                        List.of(
                                elytra(1, 200, false, true),
                                elytra(2, 220, true, false),
                                elytra(10, 8, false, false),
                                elytra(12, 120, false, false),
                                elytra(14, 120, false, false),
                                chestplate(16, 5000.0, 200, false)
                        ),
                        8
                ).orElseThrow();

        assertEquals(12, selected.inventorySlot());
        assertEquals(
                ChestSwapDecisionEngine26.CandidateKind.ELYTRA,
                selected.kind()
        );
    }

    @Test
    void selectsBestChestplateByArmorThenDurability() {
        ChestSwapDecisionEngine26.Candidate selected =
                ChestSwapDecisionEngine26.selectCandidate(
                        true,
                        List.of(
                                chestplate(10, 6000.2, 100, false),
                                chestplate(12, 7000.1, 60, false),
                                chestplate(14, 7000.1, 80, false),
                                chestplate(16, 9000.0, 200, true),
                                elytra(18, 400, false, false)
                        ),
                        10
                ).orElseThrow();

        assertEquals(14, selected.inventorySlot());
        assertEquals(
                ChestSwapDecisionEngine26.CandidateKind.CHESTPLATE,
                selected.kind()
        );
    }

    @Test
    void rejectsMalformedBrokenCursedAndUnwearableCandidates() {
        List<ChestSwapDecisionEngine26.Candidate> rejected = List.of(
                elytra(-1, 100, false, false),
                elytra(36, 100, false, false),
                elytra(10, 10, false, false),
                elytra(11, 100, true, false),
                new ChestSwapDecisionEngine26.Candidate(
                        12,
                        ChestSwapDecisionEngine26.CandidateKind.ELYTRA,
                        false,
                        false,
                        100,
                        0.0,
                        false,
                        false
                )
        );

        assertTrue(ChestSwapDecisionEngine26.selectCandidate(
                false,
                rejected,
                10
        ).isEmpty());
        assertTrue(ChestSwapDecisionEngine26.selectCandidate(
                false,
                null,
                10
        ).isEmpty());
        assertTrue(ChestSwapDecisionEngine26.selectCandidate(
                false,
                List.of(elytra(12, 100, false, false)),
                -1
        ).isEmpty());
    }

    @Test
    void heldEnabledStateCannotOscillateAfterAppliedSwap() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);
        primeDisabled(engine, 71L);

        ChestSwapDecisionEngine26.Decision swap =
                engine.step(ready(71L, true, 12));
        assertTrue(swap.apply());
        engine.commit(
                swap,
                ChestSwapDecisionEngine26.Outcome.APPLIED
        );

        for (int tick = 0; tick < 30; tick++) {
            ChestSwapDecisionEngine26.Decision held =
                    engine.step(ready(71L, true, 14));
            assertFalse(held.apply());
            assertEquals(
                    ChestSwapDecisionEngine26.BlockReason.HELD_ENABLED,
                    held.blockReason()
            );
        }
        assertEquals(
                ChestSwapDecisionEngine26.Terminal.APPLIED,
                engine.snapshot().terminal()
        );
        assertFalse(engine.snapshot().armed());
    }

    @Test
    void initiallyHeldEnableRequiresObservedFalseBeforeArming() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);

        assertEquals(
                ChestSwapDecisionEngine26.BlockReason.SESSION_WARMUP,
                engine.step(ready(711L, true, 12)).blockReason()
        );
        assertFalse(engine.snapshot().armed());
        assertEquals(
                ChestSwapDecisionEngine26.BlockReason.HELD_ENABLED,
                engine.step(ready(711L, true, 12)).blockReason()
        );

        engine.step(ready(711L, false, -1));
        assertTrue(engine.step(ready(711L, true, 12)).apply());
    }

    @Test
    void observingDisableThenReenableRearmsExactlyOnce() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);
        primeDisabled(engine, 72L);
        ChestSwapDecisionEngine26.Decision first =
                engine.step(ready(72L, true, 12));
        engine.commit(
                first,
                ChestSwapDecisionEngine26.Outcome.APPLIED
        );

        for (int tick = 0; tick < 3; tick++) {
            engine.step(ready(72L, false, -1));
        }
        ChestSwapDecisionEngine26.Decision second =
                engine.step(ready(72L, true, 14));
        assertTrue(second.apply());
        engine.commit(
                second,
                ChestSwapDecisionEngine26.Outcome.APPLIED
        );

        assertFalse(engine.step(
                ready(72L, true, 12)
        ).apply());
    }

    @Test
    void noCandidateIsTerminalUntilDisable() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);
        primeDisabled(engine, 73L);
        ChestSwapDecisionEngine26.Decision missing =
                engine.step(ready(73L, true, -1));

        assertEquals(
                ChestSwapDecisionEngine26.BlockReason.NO_CANDIDATE,
                missing.blockReason()
        );
        assertEquals(
                ChestSwapDecisionEngine26.Terminal.NO_CANDIDATE,
                engine.snapshot().terminal()
        );
        assertFalse(engine.step(
                ready(73L, true, 12)
        ).apply());

        for (int tick = 0; tick < 5; tick++) {
            engine.step(ready(73L, false, -1));
        }
        assertTrue(engine.step(
                ready(73L, true, 12)
        ).apply());
    }

    @Test
    void arbitrationDenialHasBoundedWaitAndThenTerminates() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);
        primeDisabled(engine, 74L);
        for (int attempt = 0; attempt < 3; attempt++) {
            ChestSwapDecisionEngine26.Decision decision =
                    engine.step(ready(74L, true, 12));
            assertTrue(decision.apply());
            engine.commit(
                    decision,
                    ChestSwapDecisionEngine26.Outcome.DENIED
            );
        }

        assertEquals(
                ChestSwapDecisionEngine26.Terminal.CONFLICT_TIMEOUT,
                engine.snapshot().terminal()
        );
        assertFalse(engine.snapshot().armed());
        assertFalse(engine.step(
                ready(74L, true, 12)
        ).apply());
    }

    @Test
    void utilityHotbarConflictNeverProducesAPlanAndIsBounded() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);
        primeDisabled(engine, 75L);
        for (int tick = 0; tick < 3; tick++) {
            ChestSwapDecisionEngine26.Decision decision =
                    engine.step(conflicted(75L));
            assertFalse(decision.apply());
            assertEquals(
                    ChestSwapDecisionEngine26.BlockReason.UTILITY_CONFLICT,
                    decision.blockReason()
            );
        }
        assertEquals(
                ChestSwapDecisionEngine26.Terminal.CONFLICT_TIMEOUT,
                engine.snapshot().terminal()
        );
    }

    @Test
    void invalidatedTransactionYieldsUntilExplicitRearm() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);
        primeDisabled(engine, 76L);
        ChestSwapDecisionEngine26.Decision decision =
                engine.step(ready(76L, true, 12));
        engine.commit(
                decision,
                ChestSwapDecisionEngine26.Outcome.INVALIDATED
        );

        assertEquals(
                ChestSwapDecisionEngine26.Terminal.MANUAL_YIELD,
                engine.snapshot().terminal()
        );
        assertFalse(engine.step(
                ready(76L, true, 14)
        ).apply());
    }

    @Test
    void reconnectClearsOutstandingOwnershipAndWarmsUp() {
        ChestSwapDecisionEngine26 engine =
                new ChestSwapDecisionEngine26(TIMING);
        primeDisabled(engine, 77L);
        ChestSwapDecisionEngine26.Decision pending =
                engine.step(ready(77L, true, 12));
        assertTrue(pending.apply());

        ChestSwapDecisionEngine26.Decision reconnect =
                engine.step(ready(78L, true, 14));
        assertEquals(
                ChestSwapDecisionEngine26.BlockReason.SESSION_WARMUP,
                reconnect.blockReason()
        );
        assertFalse(engine.snapshot().armed());
        assertFalse(engine.step(
                ready(78L, true, 14)
        ).apply());
        engine.step(ready(78L, false, -1));
        assertTrue(engine.step(ready(78L, true, 14)).apply());
    }

    @Test
    void timingValidationRejectsUnboundedValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChestSwapDecisionEngine26.Timing(0, 5, 3)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChestSwapDecisionEngine26.Timing(3, 41, 3)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChestSwapDecisionEngine26.Timing(3, 5, 21)
        );
    }

    private static ChestSwapDecisionEngine26.Candidate elytra(
            int slot,
            int remaining,
            boolean cursed,
            boolean selected
    ) {
        return new ChestSwapDecisionEngine26.Candidate(
                slot,
                ChestSwapDecisionEngine26.CandidateKind.ELYTRA,
                true,
                cursed,
                remaining,
                0.0,
                slot >= 0 && slot < 9,
                selected
        );
    }

    private static ChestSwapDecisionEngine26.Candidate chestplate(
            int slot,
            double armorScore,
            int remaining,
            boolean cursed
    ) {
        return new ChestSwapDecisionEngine26.Candidate(
                slot,
                ChestSwapDecisionEngine26.CandidateKind.CHESTPLATE,
                true,
                cursed,
                remaining,
                armorScore,
                slot >= 0 && slot < 9,
                false
        );
    }

    private static ChestSwapDecisionEngine26.Observation ready(
            long session,
            boolean enabled,
            int candidateSlot
    ) {
        return new ChestSwapDecisionEngine26.Observation(
                session,
                enabled,
                true,
                true,
                false,
                candidateSlot
        );
    }

    private static ChestSwapDecisionEngine26.Observation conflicted(
            long session
    ) {
        return new ChestSwapDecisionEngine26.Observation(
                session,
                true,
                true,
                false,
                true,
                0
        );
    }

    private static void primeDisabled(
            ChestSwapDecisionEngine26 engine,
            long session
    ) {
        assertEquals(
                ChestSwapDecisionEngine26.BlockReason.SESSION_WARMUP,
                engine.step(ready(session, false, -1)).blockReason()
        );
        assertTrue(engine.snapshot().armed());
    }
}
