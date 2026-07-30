package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoArmorDecisionEngine26Test {
    private static final AutoArmorDecisionEngine26.Timing TIMING =
            new AutoArmorDecisionEngine26.Timing(4, 2);

    @Test
    void strongestRealUpgradeWinsIndependentlyOfCandidateOrder() {
        var helmet = candidate(
                12,
                AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                4_000.0,
                100,
                false,
                false
        );
        var chest = candidate(
                14,
                AutoArmorDecisionEngine26.ArmorSlot.CHEST,
                9_000.0,
                80,
                false,
                false
        );
        var currentHelmet = equipped(
                AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                1_000.0,
                false,
                false,
                false
        );
        var currentChest = equipped(
                AutoArmorDecisionEngine26.ArmorSlot.CHEST,
                7_000.0,
                false,
                false,
                false
        );

        var forward = AutoArmorDecisionEngine26.selectBestUpgrade(
                List.of(currentHelmet, currentChest),
                List.of(helmet, chest),
                true,
                3,
                0.001
        ).orElseThrow();
        var reverse = AutoArmorDecisionEngine26.selectBestUpgrade(
                List.of(currentChest, currentHelmet),
                List.of(chest, helmet),
                true,
                3,
                0.001
        ).orElseThrow();

        assertEquals(12, forward.candidate().inventorySlot());
        assertEquals(forward, reverse);
        assertEquals(3_000.0, forward.improvement());
    }

    @Test
    void tieBreakPrefersDurabilityThenMainInventoryThenSlot() {
        var current = equipped(
                AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                1_000.0,
                false,
                false,
                false
        );
        var selectedHotbar = candidate(
                0,
                AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                2_000.0,
                500,
                false,
                true
        );
        var hotbar = candidate(
                1,
                AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                2_000.0,
                100,
                false,
                false
        );
        var inventoryHighSlot = candidate(
                18,
                AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                2_000.0,
                100,
                false,
                false
        );
        var inventoryLowSlot = candidate(
                12,
                AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                2_000.0,
                100,
                false,
                false
        );

        var selected = AutoArmorDecisionEngine26.selectBestUpgrade(
                List.of(current),
                List.of(
                        selectedHotbar,
                        hotbar,
                        inventoryHighSlot,
                        inventoryLowSlot
                ),
                true,
                3,
                0.001
        ).orElseThrow();

        assertEquals(12, selected.candidate().inventorySlot());
    }

    @Test
    void preserveElytraAndBindingCursePreventReplacement() {
        var chestplate = candidate(
                12,
                AutoArmorDecisionEngine26.ArmorSlot.CHEST,
                8_000.0,
                100,
                false,
                false
        );
        var elytra = equipped(
                AutoArmorDecisionEngine26.ArmorSlot.CHEST,
                -1.0,
                false,
                true,
                false
        );
        var cursedEquipped = equipped(
                AutoArmorDecisionEngine26.ArmorSlot.CHEST,
                1_000.0,
                false,
                false,
                true
        );

        assertTrue(AutoArmorDecisionEngine26.selectBestUpgrade(
                List.of(elytra),
                List.of(chestplate),
                true,
                3,
                0.001
        ).isEmpty());
        assertEquals(
                12,
                AutoArmorDecisionEngine26.selectBestUpgrade(
                        List.of(elytra),
                        List.of(chestplate),
                        false,
                        3,
                        0.001
                ).orElseThrow().candidate().inventorySlot()
        );
        assertTrue(AutoArmorDecisionEngine26.selectBestUpgrade(
                List.of(cursedEquipped),
                List.of(chestplate),
                false,
                3,
                0.001
        ).isEmpty());
    }

    @Test
    void rejectsBrokenCursedSelectedMalformedAndNonImprovingCandidates() {
        var current = equipped(
                AutoArmorDecisionEngine26.ArmorSlot.FEET,
                2_000.0,
                false,
                false,
                false
        );
        List<AutoArmorDecisionEngine26.Candidate> rejected = List.of(
                candidate(
                        10,
                        AutoArmorDecisionEngine26.ArmorSlot.FEET,
                        4_000.0,
                        3,
                        false,
                        false
                ),
                candidate(
                        11,
                        AutoArmorDecisionEngine26.ArmorSlot.FEET,
                        4_000.0,
                        100,
                        true,
                        false
                ),
                candidate(
                        2,
                        AutoArmorDecisionEngine26.ArmorSlot.FEET,
                        4_000.0,
                        100,
                        false,
                        true
                ),
                candidate(
                        13,
                        AutoArmorDecisionEngine26.ArmorSlot.FEET,
                        2_000.0,
                        100,
                        false,
                        false
                ),
                candidate(
                        36,
                        AutoArmorDecisionEngine26.ArmorSlot.FEET,
                        9_000.0,
                        100,
                        false,
                        false
                )
        );

        assertTrue(AutoArmorDecisionEngine26.selectBestUpgrade(
                List.of(current),
                rejected,
                true,
                3,
                0.001
        ).isEmpty());
        assertTrue(AutoArmorDecisionEngine26.selectBestUpgrade(
                null,
                rejected,
                true,
                3,
                0.001
        ).isEmpty());
    }

    @Test
    void sessionWarmupAndUtilityOwnershipFailClosed() {
        AutoArmorDecisionEngine26 engine =
                new AutoArmorDecisionEngine26(TIMING);

        assertEquals(
                AutoArmorDecisionEngine26.BlockReason.SESSION_WARMUP,
                engine.step(observation(41L, 0L, false, false))
                        .blockReason()
        );
        assertEquals(
                AutoArmorDecisionEngine26.BlockReason.UTILITY_HOTBAR,
                engine.step(observation(41L, 1L, true, false))
                        .blockReason()
        );
        assertTrue(
                engine.step(observation(41L, 2L, false, false))
                        .apply()
        );
    }

    @Test
    void duplicateSubmitCannotPrepareTwoActionsForOneTick() {
        AutoArmorDecisionEngine26 engine =
                readyEngine(42L);

        AutoArmorDecisionEngine26.Decision first =
                engine.step(observation(42L, 1L, false, false));
        AutoArmorDecisionEngine26.Decision duplicate =
                engine.step(observation(42L, 1L, false, false));

        assertTrue(first.apply());
        assertFalse(duplicate.apply());
        assertEquals(
                AutoArmorDecisionEngine26.BlockReason.DUPLICATE_TICK,
                duplicate.blockReason()
        );
        engine.commit(
                first,
                AutoArmorDecisionEngine26.Outcome.EXECUTED
        );
        assertEquals(0, engine.snapshot().cooldownTicks());
    }

    @Test
    void onlyExecutedActionStartsBoundedCooldown() {
        AutoArmorDecisionEngine26 engine =
                readyEngine(43L);
        AutoArmorDecisionEngine26.Decision denied =
                engine.step(observation(43L, 1L, false, false));
        engine.commit(
                denied,
                AutoArmorDecisionEngine26.Outcome.DENIED
        );
        assertEquals(0, engine.snapshot().cooldownTicks());

        AutoArmorDecisionEngine26.Decision executed =
                engine.step(observation(43L, 2L, false, false));
        engine.commit(
                executed,
                AutoArmorDecisionEngine26.Outcome.EXECUTED
        );
        assertEquals(4, engine.snapshot().cooldownTicks());

        for (long tick = 3L; tick < 6L; tick++) {
            assertEquals(
                    AutoArmorDecisionEngine26.BlockReason.COOLDOWN,
                    engine.step(observation(
                            43L,
                            tick,
                            false,
                            false
                    )).blockReason()
            );
        }
        assertTrue(
                engine.step(observation(43L, 6L, false, false))
                        .apply()
        );
    }

    @Test
    void stalePlanAndManualInputYieldBeforeRetrying() {
        AutoArmorDecisionEngine26 engine =
                readyEngine(44L);
        AutoArmorDecisionEngine26.Decision stale =
                engine.step(observation(44L, 1L, false, false));
        engine.commit(
                stale,
                AutoArmorDecisionEngine26.Outcome.STALE
        );
        assertEquals(2, engine.snapshot().manualYieldTicks());
        assertEquals(
                AutoArmorDecisionEngine26.BlockReason.MANUAL_YIELD,
                engine.step(observation(44L, 2L, false, false))
                        .blockReason()
        );
        assertTrue(
                engine.step(observation(44L, 3L, false, false))
                        .apply()
        );

        assertEquals(
                AutoArmorDecisionEngine26.BlockReason.MANUAL_CHANGE,
                engine.step(observation(44L, 4L, false, true))
                        .blockReason()
        );
        assertEquals(
                AutoArmorDecisionEngine26.BlockReason.MANUAL_YIELD,
                engine.step(observation(44L, 5L, false, false))
                        .blockReason()
        );
        assertTrue(
                engine.step(observation(44L, 6L, false, false))
                        .apply()
        );
    }

    @Test
    void reconnectAndResetClearCooldownOutstandingAndYield() {
        AutoArmorDecisionEngine26 engine =
                readyEngine(45L);
        AutoArmorDecisionEngine26.Decision decision =
                engine.step(observation(45L, 1L, false, false));
        engine.commit(
                decision,
                AutoArmorDecisionEngine26.Outcome.EXECUTED
        );

        assertEquals(
                AutoArmorDecisionEngine26.BlockReason.SESSION_WARMUP,
                engine.step(observation(46L, 2L, false, false))
                        .blockReason()
        );
        assertEquals(0, engine.snapshot().cooldownTicks());
        assertFalse(engine.snapshot().outstandingAction());

        engine.reset();
        assertEquals(Long.MIN_VALUE, engine.snapshot().sessionKey());
        assertEquals(0, engine.snapshot().manualYieldTicks());
    }

    @Test
    void timingRejectsUnboundedValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoArmorDecisionEngine26.Timing(0, 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoArmorDecisionEngine26.Timing(21, 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoArmorDecisionEngine26.Timing(4, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoArmorDecisionEngine26.Timing(4, 11)
        );
    }

    private static AutoArmorDecisionEngine26 readyEngine(long session) {
        AutoArmorDecisionEngine26 engine =
                new AutoArmorDecisionEngine26(TIMING);
        engine.step(observation(session, 0L, false, false));
        return engine;
    }

    private static AutoArmorDecisionEngine26.Observation observation(
            long session,
            long tick,
            boolean utilityHotbarOwned,
            boolean manualChange
    ) {
        return new AutoArmorDecisionEngine26.Observation(
                session,
                tick,
                true,
                true,
                true,
                utilityHotbarOwned,
                manualChange,
                true,
                3,
                0.001,
                List.of(equipped(
                        AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                        1_000.0,
                        false,
                        false,
                        false
                )),
                List.of(candidate(
                        12,
                        AutoArmorDecisionEngine26.ArmorSlot.HEAD,
                        4_000.0,
                        100,
                        false,
                        false
                ))
        );
    }

    private static AutoArmorDecisionEngine26.Candidate candidate(
            int inventorySlot,
            AutoArmorDecisionEngine26.ArmorSlot armorSlot,
            double score,
            int remainingDurability,
            boolean bindingCursed,
            boolean selectedHotbar
    ) {
        return new AutoArmorDecisionEngine26.Candidate(
                inventorySlot,
                armorSlot,
                score,
                remainingDurability,
                bindingCursed,
                selectedHotbar
        );
    }

    private static AutoArmorDecisionEngine26.EquippedArmor equipped(
            AutoArmorDecisionEngine26.ArmorSlot armorSlot,
            double score,
            boolean empty,
            boolean elytra,
            boolean bindingCursed
    ) {
        return new AutoArmorDecisionEngine26.EquippedArmor(
                armorSlot,
                score,
                empty,
                elytra,
                bindingCursed
        );
    }
}
