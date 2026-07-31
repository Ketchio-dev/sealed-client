package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMendDecisionEngine26Test {
    private static final AutoMendDecisionEngine26.Configuration CONFIG =
            new AutoMendDecisionEngine26.Configuration(
                    65,
                    90,
                    2,
                    true,
                    4
            );
    private final Object session = new Object();

    @Test
    void startsAtThresholdAndStopsOnlyWhenEveryPieceIsRepaired() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);

        assertEquals(
                AutoMendDecisionEngine26.Action.NONE,
                engine.step(observation(34, 10, true)).action()
        );
        assertEquals(
                AutoMendDecisionEngine26.Action.THROW,
                engine.step(observation(35, 10, true)).action()
        );

        AutoMendDecisionEngine26.Decision throwBottle =
                engine.step(observation(35, 10, true));
        engine.commit(throwBottle, true);
        assertEquals(
                AutoMendDecisionEngine26.Action.HOLD,
                engine.step(observation(20, 9, true)).action()
        );
        assertTrue(engine.snapshot().confirmedRepairEvents() > 0);

        assertEquals(
                AutoMendDecisionEngine26.BlockReason.REPAIRED,
                engine.step(observation(10, 9, true)).blockReason()
        );
        assertFalse(engine.snapshot().mending());
    }

    @Test
    void successfulUseWaitsForActualDurabilityProgress() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        AutoMendDecisionEngine26.Decision first =
                engine.step(observation(40, 12, true));
        engine.commit(first, true);

        for (int tick = 0; tick < 4; tick++) {
            AutoMendDecisionEngine26.Decision wait =
                    engine.step(observation(40, 11, true));
            assertEquals(
                    AutoMendDecisionEngine26.Action.HOLD,
                    wait.action()
            );
            assertEquals(
                    AutoMendDecisionEngine26.BlockReason
                            .AWAITING_DURABILITY,
                    wait.blockReason()
            );
        }

        AutoMendDecisionEngine26.Decision timeout =
                engine.step(observation(40, 11, true));
        assertEquals(AutoMendDecisionEngine26.Action.HOLD, timeout.action());
        assertEquals(
                AutoMendDecisionEngine26.BlockReason.COOLDOWN,
                timeout.blockReason()
        );
        assertEquals(0, engine.snapshot().confirmedRepairEvents());
    }

    @Test
    void deniedUseDoesNotEnterConfirmation() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        AutoMendDecisionEngine26.Decision decision =
                engine.step(observation(50, 8, true));

        engine.commit(decision, false);

        assertFalse(engine.snapshot().awaitingRepair());
        assertEquals(
                AutoMendDecisionEngine26.Action.THROW,
                engine.step(observation(50, 8, true)).action()
        );
    }

    @Test
    void sneakSafetyScreenBottleAndMendingEligibilityFailClosed() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        assertEquals(
                AutoMendDecisionEngine26.BlockReason.SNEAK_REQUIRED,
                engine.step(observation(50, 8, false)).blockReason()
        );
        assertEquals(
                AutoMendDecisionEngine26.BlockReason.SAFETY,
                engine.step(new AutoMendDecisionEngine26.Observation(
                        session,
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        0,
                        0.0F,
                        1,
                        8,
                        armor(50)
                )).blockReason()
        );
        assertEquals(
                AutoMendDecisionEngine26.BlockReason.NO_BOTTLES,
                engine.step(new AutoMendDecisionEngine26.Observation(
                        session,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        0,
                        0.0F,
                        -1,
                        0,
                        armor(50)
                )).blockReason()
        );
        assertEquals(
                AutoMendDecisionEngine26.BlockReason.NO_MENDING_ARMOR,
                engine.step(new AutoMendDecisionEngine26.Observation(
                        session,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        0,
                        0.0F,
                        1,
                        8,
                        List.of()
                )).blockReason()
        );
    }

    @Test
    void sessionChangeDropsAllPendingConfirmationState() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        AutoMendDecisionEngine26.Decision decision =
                engine.step(observation(50, 8, true));
        engine.commit(decision, true);
        assertTrue(engine.snapshot().awaitingRepair());

        AutoMendDecisionEngine26.Decision reconnect =
                engine.step(new AutoMendDecisionEngine26.Observation(
                        new Object(),
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        0,
                        0.0F,
                        1,
                        8,
                        armor(50)
                ));

        assertEquals(AutoMendDecisionEngine26.Action.THROW,
                reconnect.action());
        assertFalse(engine.snapshot().awaitingRepair());
    }

    @Test
    void safetyPausePreservesOutstandingDurabilityConfirmation() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        AutoMendDecisionEngine26.Decision use =
                engine.step(observation(50, 8, true));
        engine.commit(use, true);

        AutoMendDecisionEngine26.Observation unsafe =
                new AutoMendDecisionEngine26.Observation(
                        session,
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        0,
                        0.0F,
                        1,
                        7,
                        armor(50)
                );
        AutoMendDecisionEngine26.Decision waiting =
                engine.step(unsafe);
        assertEquals(
                AutoMendDecisionEngine26.Action.HOLD,
                waiting.action()
        );
        assertFalse(waiting.requiresOwnership());
        assertTrue(engine.snapshot().awaitingRepair());
        assertEquals(0, engine.snapshot().confirmationAge());

        engine.step(observation(40, 7, true));
        assertFalse(engine.snapshot().awaitingRepair());
        assertEquals(1, engine.snapshot().confirmedRepairEvents());
    }

    @Test
    void armorReplacementOrRemovalCannotMasqueradeAsRepair() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        AutoMendDecisionEngine26.Decision use =
                engine.step(observation(50, 8, true));
        engine.commit(use, true);

        AutoMendDecisionEngine26.Observation replacement =
                new AutoMendDecisionEngine26.Observation(
                        session,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        0,
                        0.0F,
                        1,
                        7,
                        List.of(new AutoMendDecisionEngine26.ArmorPiece(
                                "head",
                                "netherite_helmet#mending",
                                5,
                                100
                        ))
                );
        assertEquals(
                AutoMendDecisionEngine26.BlockReason.ARMOR_CHANGED,
                engine.step(replacement).blockReason()
        );
        assertEquals(0, engine.snapshot().confirmedRepairEvents());
        assertFalse(engine.snapshot().awaitingRepair());
    }

    @Test
    void perPieceRepairConfirmsEvenWhenAggregateDamageRises() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        AutoMendDecisionEngine26.Observation baseline =
                observationWithArmor(List.of(
                        piece("head", "diamond_helmet#mending", 50),
                        piece("chest", "diamond_chestplate#mending", 10)
                ));
        AutoMendDecisionEngine26.Decision use = engine.step(baseline);
        engine.commit(use, true);

        AutoMendDecisionEngine26.Decision observation = engine.step(
                observationWithArmor(List.of(
                        piece("head", "diamond_helmet#mending", 40),
                        piece("chest", "diamond_chestplate#mending", 30)
                ))
        );

        assertEquals(AutoMendDecisionEngine26.Action.HOLD,
                observation.action());
        assertEquals(1, engine.snapshot().confirmedRepairEvents());
        assertFalse(engine.snapshot().awaitingRepair());
    }

    @Test
    void disablingClearsOutstandingConfirmation() {
        AutoMendDecisionEngine26 engine =
                new AutoMendDecisionEngine26(CONFIG);
        AutoMendDecisionEngine26.Decision use =
                engine.step(observation(50, 8, true));
        engine.commit(use, true);

        AutoMendDecisionEngine26.Observation enabled =
                observation(50, 7, true);
        AutoMendDecisionEngine26.Decision disabled = engine.step(
                new AutoMendDecisionEngine26.Observation(
                        enabled.sessionIdentity(),
                        false,
                        enabled.sessionReady(),
                        enabled.safetyReady(),
                        enabled.screenClear(),
                        enabled.playerAlive(),
                        enabled.sneaking(),
                        enabled.selectedSlot(),
                        enabled.pitch(),
                        enabled.bottleSlot(),
                        enabled.bottleCount(),
                        enabled.armor()
                )
        );

        assertEquals(AutoMendDecisionEngine26.BlockReason.DISABLED,
                disabled.blockReason());
        assertFalse(engine.snapshot().awaitingRepair());
        assertFalse(engine.snapshot().mending());
    }

    @Test
    void durabilityRoundsRatherThanTruncating() {
        // The existing case above uses a maximum of 100, where rounding and
        // truncation agree, so it never noticed that this engine truncated
        // while 1.21.4 rounded. On a diamond chestplate the two answers differ
        // for half of all durability values and move the repair threshold.
        AutoMendDecisionEngine26.ArmorPiece chestplate =
                new AutoMendDecisionEngine26.ArmorPiece(
                        "chest",
                        "diamond_chestplate#mending",
                        365,
                        528
                );
        assertEquals(31, chestplate.remainingPercent());
    }

    @Test
    void durabilityMathAndConfigurationAreValidated() {
        AutoMendDecisionEngine26.ArmorPiece piece =
                new AutoMendDecisionEngine26.ArmorPiece(
                        "head",
                        "diamond_helmet#mending",
                        35,
                        100
                );
        assertEquals(65, piece.remainingPercent());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMendDecisionEngine26.ArmorPiece(
                        "head",
                        "diamond_helmet#mending",
                        101,
                        100
                )
        );
        assertEquals(
                70,
                new AutoMendDecisionEngine26.Configuration(
                        70, 60, 2, true, 20
                ).stopAtPercent()
        );
    }

    private AutoMendDecisionEngine26.Observation observation(
            int damage,
            int bottles,
            boolean sneaking
    ) {
        return new AutoMendDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                true,
                sneaking,
                0,
                12.0F,
                1,
                bottles,
                armor(damage)
        );
    }

    private static List<AutoMendDecisionEngine26.ArmorPiece> armor(
            int damage
    ) {
        return List.of(
                piece("head", "diamond_helmet#mending", damage),
                piece(
                        "chest",
                        "diamond_chestplate#mending",
                        Math.min(damage, 10)
                )
        );
    }

    private AutoMendDecisionEngine26.Observation observationWithArmor(
            List<AutoMendDecisionEngine26.ArmorPiece> armor
    ) {
        return new AutoMendDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                true,
                true,
                0,
                12.0F,
                1,
                8,
                armor
        );
    }

    private static AutoMendDecisionEngine26.ArmorPiece piece(
            String slot,
            String token,
            int damage
    ) {
        return new AutoMendDecisionEngine26.ArmorPiece(
                slot,
                token,
                damage,
                100
        );
    }
}
