package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCraftDecisionEngine26Test {
    private static final AutoCraftDecisionEngine26.Configuration CONFIG =
            new AutoCraftDecisionEngine26.Configuration(2, 2, 5);
    private static final AutoCraftDecisionEngine26.Candidate CANDIDATE =
            new AutoCraftDecisionEngine26.Candidate(
                    "shaped:minecraft:ender_chest",
                    "minecraft:ender_chest",
                    "abc:1",
                    1
            );
    private final Object session = new Object();

    @Test
    void placePickupAndActualTargetConfirmationAreSeparateActions() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(CONFIG);
        AutoCraftDecisionEngine26.Decision place =
                engine.step(idle(CANDIDATE));
        assertEquals(
                AutoCraftDecisionEngine26.Action.PLACE_RECIPE,
                place.action()
        );
        engine.commit(place, true);

        assertEquals(
                AutoCraftDecisionEngine26.Phase.AWAITING_OUTPUT,
                engine.snapshot().phase()
        );
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.COOLDOWN,
                engine.step(output(false, false)).blockReason()
        );
        AutoCraftDecisionEngine26.Decision pickup =
                engine.step(output(true, false));
        assertEquals(
                AutoCraftDecisionEngine26.Action.PICKUP_OUTPUT,
                pickup.action()
        );
        engine.commit(pickup, true);
        assertEquals(
                AutoCraftDecisionEngine26.Phase.AWAITING_PICKUP,
                engine.snapshot().phase()
        );

        assertEquals(
                AutoCraftDecisionEngine26.BlockReason
                        .WAITING_FOR_PICKUP_CONFIRMATION,
                engine.step(output(true, false)).blockReason()
        );
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.PICKUP_CONFIRMED,
                engine.step(output(true, true)).blockReason()
        );
        assertEquals(1, engine.snapshot().completedCrafts());
        assertNull(engine.snapshot().pendingCandidate());
    }

    @Test
    void dualWhitelistRequiresRecipeAndOutputIndependently() {
        Set<String> recipes =
                Set.of("shaped:minecraft:ender_chest");
        Set<String> outputs = Set.of("minecraft:ender_chest");
        assertTrue(AutoCraftRecipeSelector26.allowed(
                "shaped:minecraft:ender_chest",
                "minecraft:ender_chest",
                recipes,
                outputs
        ));
        assertFalse(AutoCraftRecipeSelector26.allowed(
                "shapeless:minecraft:ender_chest",
                "minecraft:ender_chest",
                recipes,
                outputs
        ));
        assertFalse(AutoCraftRecipeSelector26.allowed(
                "shaped:minecraft:ender_chest",
                "minecraft:chest",
                recipes,
                outputs
        ));
    }

    @Test
    void emptyCursorGridAndPreexistingOutputAreMandatory() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(CONFIG);
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.CURSOR_NOT_EMPTY,
                engine.step(copy(idle(CANDIDATE), false, true, false))
                        .blockReason()
        );
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.GRID_NOT_EMPTY,
                engine.step(copy(idle(CANDIDATE), true, false, false))
                        .blockReason()
        );
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.PREEXISTING_OUTPUT,
                engine.step(copy(idle(CANDIDATE), true, true, true))
                        .blockReason()
        );
    }

    @Test
    void unexpectedOutputAndTimeoutAbandonWithoutBlindPickup() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(CONFIG);
        AutoCraftDecisionEngine26.Decision place =
                engine.step(idle(CANDIDATE));
        engine.commit(place, true);
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.UNEXPECTED_OUTPUT,
                engine.step(new AutoCraftDecisionEngine26.Observation(
                        session,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true,
                        false,
                        true,
                        false,
                        false,
                        null
                )).blockReason()
        );
        assertEquals(
                AutoCraftDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );

        engine = new AutoCraftDecisionEngine26(CONFIG);
        place = engine.step(idle(CANDIDATE));
        engine.commit(place, true);
        AutoCraftDecisionEngine26.Decision timeout = null;
        for (int tick = 0;
             tick <= CONFIG.confirmationTimeoutTicks();
             tick++) {
            timeout = engine.step(output(false, false));
        }
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.OUTPUT_TIMEOUT,
                timeout.blockReason()
        );
        assertEquals(
                AutoCraftDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );
    }

    @Test
    void screenAndSessionLifecycleResetPendingAndCraftLimit() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(
                        new AutoCraftDecisionEngine26.Configuration(
                                2,
                                1,
                                5
                        )
                );
        completeOne(engine);
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.SESSION_LIMIT,
                engine.step(idle(CANDIDATE)).blockReason()
        );

        AutoCraftDecisionEngine26.Observation reopened =
                new AutoCraftDecisionEngine26.Observation(
                        new Object(),
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        CANDIDATE
                );
        assertEquals(
                AutoCraftDecisionEngine26.Action.PLACE_RECIPE,
                engine.step(reopened).action()
        );

        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.NOT_CRAFTING_SCREEN,
                engine.step(new AutoCraftDecisionEngine26.Observation(
                        new Object(),
                        true,
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null
                )).blockReason()
        );
        assertEquals(0, engine.snapshot().completedCrafts());
    }

    @Test
    void temporarySafetyPausePreservesScreenLimit() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(
                        new AutoCraftDecisionEngine26.Configuration(
                                2,
                                1,
                                5
                        )
                );
        completeOne(engine);

        AutoCraftDecisionEngine26.Observation base = idle(CANDIDATE);
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.SAFETY,
                engine.step(new AutoCraftDecisionEngine26.Observation(
                        base.sessionIdentity(),
                        base.enabled(),
                        base.sessionReady(),
                        false,
                        base.craftingScreen(),
                        base.playerAlive(),
                        base.cursorEmpty(),
                        base.gridEmpty(),
                        base.resultPresent(),
                        base.resultMatchesExpected(),
                        base.outputTargetAvailable(),
                        base.pickupConfirmed(),
                        base.pickupInvalidated(),
                        base.candidate()
                )).blockReason()
        );
        assertEquals(1, engine.snapshot().completedCrafts());
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.SESSION_LIMIT,
                engine.step(idle(CANDIDATE)).blockReason()
        );
    }

    @Test
    void safetyPauseAfterPickupPreservesLateConfirmation() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(CONFIG);
        AutoCraftDecisionEngine26.Decision place =
                engine.step(idle(CANDIDATE));
        engine.commit(place, true);
        engine.step(output(false, false));
        AutoCraftDecisionEngine26.Decision pickup =
                engine.step(output(true, false));
        engine.commit(pickup, true);

        AutoCraftDecisionEngine26.Observation waiting =
                output(true, false);
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.SAFETY,
                engine.step(new AutoCraftDecisionEngine26.Observation(
                        waiting.sessionIdentity(),
                        waiting.enabled(),
                        waiting.sessionReady(),
                        false,
                        waiting.craftingScreen(),
                        waiting.playerAlive(),
                        waiting.cursorEmpty(),
                        waiting.gridEmpty(),
                        waiting.resultPresent(),
                        waiting.resultMatchesExpected(),
                        waiting.outputTargetAvailable(),
                        waiting.pickupConfirmed(),
                        waiting.pickupInvalidated(),
                        waiting.candidate()
                )).blockReason()
        );
        assertEquals(
                AutoCraftDecisionEngine26.Phase.AWAITING_PICKUP,
                engine.snapshot().phase()
        );
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.PICKUP_CONFIRMED,
                engine.step(output(true, true)).blockReason()
        );
        assertEquals(1, engine.snapshot().completedCrafts());
    }

    @Test
    void disabledAfterPickupStillQuarantinesAndPreservesLimit() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(
                        new AutoCraftDecisionEngine26.Configuration(
                                2,
                                1,
                                5
                        )
                );
        AutoCraftDecisionEngine26.Decision place =
                engine.step(idle(CANDIDATE));
        engine.commit(place, true);
        engine.step(output(false, false));
        AutoCraftDecisionEngine26.Decision pickup =
                engine.step(output(true, false));
        engine.commit(pickup, true);

        AutoCraftDecisionEngine26.Observation confirmed =
                output(true, true);
        AutoCraftDecisionEngine26.Observation disabledWaiting =
                withEnabled(output(true, false), false);
        int pendingAge = engine.snapshot().pendingAge();
        for (int tick = 0; tick < 50; tick++) {
            assertEquals(
                    AutoCraftDecisionEngine26.BlockReason.SAFETY,
                    engine.step(withSafety(
                            disabledWaiting,
                            false
                    )).blockReason()
            );
        }
        assertEquals(pendingAge, engine.snapshot().pendingAge());
        assertEquals(
                AutoCraftDecisionEngine26.Phase.AWAITING_PICKUP,
                engine.snapshot().phase()
        );
        AutoCraftDecisionEngine26.Decision passive = engine.step(
                withEnabled(confirmed, false)
        );

        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.PICKUP_CONFIRMED,
                passive.blockReason()
        );
        assertEquals(1, engine.snapshot().completedCrafts());
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.SESSION_LIMIT,
                engine.step(idle(CANDIDATE)).blockReason()
        );
    }

    @Test
    void cursorInterferenceDoesNotDiscardLatePickupOrRollback() {
        AutoCraftDecisionEngine26 engine =
                new AutoCraftDecisionEngine26(CONFIG);
        AutoCraftDecisionEngine26.Decision place =
                engine.step(idle(CANDIDATE));
        engine.commit(place, true);
        engine.step(output(false, false));
        AutoCraftDecisionEngine26.Decision pickup =
                engine.step(output(true, false));
        engine.commit(pickup, true);

        AutoCraftDecisionEngine26.Observation waiting =
                copy(output(true, false), false, false, true);
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.CURSOR_NOT_EMPTY,
                engine.step(waiting).blockReason()
        );
        assertEquals(
                AutoCraftDecisionEngine26.Phase.AWAITING_PICKUP,
                engine.snapshot().phase()
        );

        AutoCraftDecisionEngine26.Observation rollback =
                withPickupInvalidated(output(true, false));
        assertEquals(
                AutoCraftDecisionEngine26.BlockReason.PICKUP_ROLLED_BACK,
                engine.step(rollback).blockReason()
        );
        assertEquals(0, engine.snapshot().completedCrafts());
        assertEquals(
                AutoCraftDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );
    }

    @Test
    void configurationAndCandidateBoundsAreValidated() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoCraftDecisionEngine26.Configuration(
                        1,
                        8,
                        40
                )
        );
        assertEquals(
                40,
                new AutoCraftDecisionEngine26.Configuration(
                        10, 8, 5
                ).confirmationTimeoutTicks()
        );
        assertEquals(
                400,
                new AutoCraftDecisionEngine26.Configuration(
                        100, 8, 40
                ).confirmationTimeoutTicks()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoCraftDecisionEngine26.Candidate(
                        "",
                        "minecraft:stone",
                        "x",
                        1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoCraftAutomation26.Configuration(
                        Set.of(""),
                        Set.of(),
                        10,
                        8,
                        0,
                        40,
                        16,
                        100,
                        2
                )
        );
    }

    private void completeOne(AutoCraftDecisionEngine26 engine) {
        AutoCraftDecisionEngine26.Decision place =
                engine.step(idle(CANDIDATE));
        engine.commit(place, true);
        engine.step(output(false, false));
        AutoCraftDecisionEngine26.Decision pickup =
                engine.step(output(true, false));
        engine.commit(pickup, true);
        engine.step(output(true, true));
    }

    private AutoCraftDecisionEngine26.Observation idle(
            AutoCraftDecisionEngine26.Candidate candidate
    ) {
        return new AutoCraftDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                false,
                false,
                candidate
        );
    }

    private AutoCraftDecisionEngine26.Observation output(
            boolean present,
            boolean pickupConfirmed
    ) {
        return new AutoCraftDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                present,
                present,
                true,
                pickupConfirmed,
                false,
                null
        );
    }

    private static AutoCraftDecisionEngine26.Observation copy(
            AutoCraftDecisionEngine26.Observation source,
            boolean cursorEmpty,
            boolean gridEmpty,
            boolean resultPresent
    ) {
        return new AutoCraftDecisionEngine26.Observation(
                source.sessionIdentity(),
                source.enabled(),
                source.sessionReady(),
                source.safetyReady(),
                source.craftingScreen(),
                source.playerAlive(),
                cursorEmpty,
                gridEmpty,
                resultPresent,
                source.resultMatchesExpected(),
                source.outputTargetAvailable(),
                source.pickupConfirmed(),
                source.pickupInvalidated(),
                source.candidate()
        );
    }

    private static AutoCraftDecisionEngine26.Observation withEnabled(
            AutoCraftDecisionEngine26.Observation source,
            boolean enabled
    ) {
        return new AutoCraftDecisionEngine26.Observation(
                source.sessionIdentity(),
                enabled,
                source.sessionReady(),
                source.safetyReady(),
                source.craftingScreen(),
                source.playerAlive(),
                source.cursorEmpty(),
                source.gridEmpty(),
                source.resultPresent(),
                source.resultMatchesExpected(),
                source.outputTargetAvailable(),
                source.pickupConfirmed(),
                source.pickupInvalidated(),
                source.candidate()
        );
    }

    private static AutoCraftDecisionEngine26.Observation withSafety(
            AutoCraftDecisionEngine26.Observation source,
            boolean safetyReady
    ) {
        return new AutoCraftDecisionEngine26.Observation(
                source.sessionIdentity(),
                source.enabled(),
                source.sessionReady(),
                safetyReady,
                source.craftingScreen(),
                source.playerAlive(),
                source.cursorEmpty(),
                source.gridEmpty(),
                source.resultPresent(),
                source.resultMatchesExpected(),
                source.outputTargetAvailable(),
                source.pickupConfirmed(),
                source.pickupInvalidated(),
                source.candidate()
        );
    }

    private static AutoCraftDecisionEngine26.Observation
            withPickupInvalidated(
            AutoCraftDecisionEngine26.Observation source
    ) {
        return new AutoCraftDecisionEngine26.Observation(
                source.sessionIdentity(),
                source.enabled(),
                source.sessionReady(),
                source.safetyReady(),
                source.craftingScreen(),
                source.playerAlive(),
                source.cursorEmpty(),
                source.gridEmpty(),
                source.resultPresent(),
                source.resultMatchesExpected(),
                source.outputTargetAvailable(),
                false,
                true,
                source.candidate()
        );
    }
}
