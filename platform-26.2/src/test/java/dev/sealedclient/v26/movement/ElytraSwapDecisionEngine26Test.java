package dev.sealedclient.v26.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraSwapDecisionEngine26Test {
    private static final ElytraSwapDecisionEngine26.Timing TIMING =
            new ElytraSwapDecisionEngine26.Timing(6, 2, 3, 4);

    @Test
    void selectsHealthyUncursedNonSelectedElytraDeterministically() {
        List<ElytraSwapDecisionEngine26.Candidate> candidates = List.of(
                candidate(2, 90, false, true, true),
                candidate(4, 80, false, true, false),
                candidate(12, 80, false, false, false),
                candidate(14, 120, true, false, false),
                candidate(15, 8, false, false, false),
                new ElytraSwapDecisionEngine26.Candidate(
                        16,
                        true,
                        false,
                        200,
                        false,
                        false,
                        false
                )
        );

        ElytraSwapDecisionEngine26.Candidate selected =
                ElytraSwapDecisionEngine26.selectBestElytra(
                        candidates,
                        8
                ).orElseThrow();

        assertEquals(12, selected.inventorySlot());
        assertEquals(80, selected.remainingDurability());
    }

    @Test
    void rejectsCursesSelectedHotbarBrokenAndMalformedCandidates() {
        assertTrue(ElytraSwapDecisionEngine26.selectBestElytra(
                List.of(
                        candidate(0, 100, false, true, true),
                        candidate(10, 100, true, false, false),
                        candidate(11, 8, false, false, false),
                        new ElytraSwapDecisionEngine26.Candidate(
                                12,
                                false,
                                true,
                                100,
                                false,
                                false,
                                false
                        )
                ),
                8
        ).isEmpty());
        assertTrue(ElytraSwapDecisionEngine26.selectBestElytra(
                null,
                8
        ).isEmpty());
        assertTrue(ElytraSwapDecisionEngine26.selectBestElytra(
                List.of(candidate(10, 100, false, false, false)),
                -1
        ).isEmpty());
    }

    @Test
    void deniedArbitrationDoesNotEnterConfirmation() {
        ElytraSwapDecisionEngine26 engine =
                new ElytraSwapDecisionEngine26(TIMING);
        engine.step(observation(41L));

        ElytraSwapDecisionEngine26.Decision equip =
                engine.step(observation(41L));
        assertEquals(
                ElytraSwapDecisionEngine26.Action.EQUIP,
                equip.action()
        );

        engine.commit(equip, false);
        assertEquals(
                ElytraSwapDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );
        assertEquals(-1, engine.snapshot().ownedSourceSlot());
    }

    @Test
    void confirmsEquipThenRestoresOnlyAfterLanding() {
        ElytraSwapDecisionEngine26 engine =
                new ElytraSwapDecisionEngine26(TIMING);
        engine.step(observation(42L));
        ElytraSwapDecisionEngine26.Decision equip =
                engine.step(observation(42L));
        engine.commit(equip, true);

        assertEquals(
                ElytraSwapDecisionEngine26.Phase.AWAITING_EQUIP,
                engine.snapshot().phase()
        );
        engine.step(owned(42L, false, true));
        ElytraSwapDecisionEngine26.Decision flying =
                engine.step(owned(42L, false, true));
        assertEquals(
                ElytraSwapDecisionEngine26.Phase.OWNED,
                engine.snapshot().phase()
        );
        assertEquals(
                ElytraSwapDecisionEngine26.BlockReason.WAITING_FOR_LANDING,
                flying.blockReason()
        );

        ElytraSwapDecisionEngine26.Decision restore =
                engine.step(owned(42L, true, true));
        assertEquals(
                ElytraSwapDecisionEngine26.Action.RESTORE,
                restore.action()
        );
        assertEquals(12, restore.inventorySlot());
        engine.commit(restore, true);

        engine.step(restored(42L));
        engine.step(restored(42L));
        assertEquals(
                ElytraSwapDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );
        assertEquals(-1, engine.snapshot().ownedSourceSlot());
        assertTrue(engine.snapshot().cooldownTicks() > 0);
    }

    @Test
    void manualEquipmentOrSourceChangeAbandonsOwnership() {
        ElytraSwapDecisionEngine26 engine = ownedEngine(43L);

        ElytraSwapDecisionEngine26.Observation manual =
                replace(
                        owned(43L, false, true),
                        false,
                        false,
                        false,
                        true
                );
        ElytraSwapDecisionEngine26.Decision decision =
                engine.step(manual);

        assertFalse(decision.apply());
        assertEquals(
                ElytraSwapDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );
        assertTrue(engine.snapshot().suppressedUntilGround());
        assertEquals(-1, engine.snapshot().ownedSourceSlot());
    }

    @Test
    void disableInFlightKeepsLeaseAndRestoresOnGround() {
        ElytraSwapDecisionEngine26 engine = ownedEngine(44L);

        ElytraSwapDecisionEngine26.Decision inFlight =
                engine.step(disabledOwned(44L, false));
        assertEquals(
                ElytraSwapDecisionEngine26.BlockReason.DISABLED_IN_FLIGHT,
                inFlight.blockReason()
        );
        assertEquals(
                ElytraSwapDecisionEngine26.Phase.OWNED,
                engine.snapshot().phase()
        );

        ElytraSwapDecisionEngine26.Decision landed =
                engine.step(disabledOwned(44L, true));
        assertEquals(
                ElytraSwapDecisionEngine26.Action.RESTORE,
                landed.action()
        );
    }

    @Test
    void unconfirmedTransactionTimesOutWithoutBlindRetry() {
        ElytraSwapDecisionEngine26 engine =
                new ElytraSwapDecisionEngine26(
                        new ElytraSwapDecisionEngine26.Timing(
                                3,
                                1,
                                2,
                                5
                        )
                );
        engine.step(observation(45L));
        ElytraSwapDecisionEngine26.Decision equip =
                engine.step(observation(45L));
        engine.commit(equip, true);

        for (int index = 0; index < 4; index++) {
            engine.step(observation(45L));
        }

        assertEquals(
                ElytraSwapDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );
        assertTrue(engine.snapshot().suppressedUntilGround());
        assertTrue(engine.snapshot().cooldownTicks() > 0);
    }

    @Test
    void reconnectClearsLeaseAndRequiresFreshWarmup() {
        ElytraSwapDecisionEngine26 engine = ownedEngine(46L);

        ElytraSwapDecisionEngine26.Decision reconnect =
                engine.step(observation(47L));

        assertEquals(
                ElytraSwapDecisionEngine26.BlockReason.SESSION_WARMUP,
                reconnect.blockReason()
        );
        assertEquals(
                ElytraSwapDecisionEngine26.Phase.IDLE,
                engine.snapshot().phase()
        );
        assertEquals(-1, engine.snapshot().ownedSourceSlot());
    }

    @Test
    void invalidTimingAndConfigurationAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ElytraSwapDecisionEngine26.Timing(1, 1, 3, 4)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ElytraSwapDecisionEngine26.Timing(5, 5, 3, 4)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ElytraSwapAutomation26.Configuration(
                        0.4,
                        8,
                        true
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ElytraSwapAutomation26.Configuration(
                        1.5,
                        1,
                        true
                )
        );
    }

    @Test
    void inventoryMenuMappingMatchesVanillaPlayerMenu() {
        assertEquals(
                36,
                ElytraSwapAutomation26.inventoryIndexToMenuSlot(0)
        );
        assertEquals(
                44,
                ElytraSwapAutomation26.inventoryIndexToMenuSlot(8)
        );
        assertEquals(
                9,
                ElytraSwapAutomation26.inventoryIndexToMenuSlot(9)
        );
        assertEquals(
                35,
                ElytraSwapAutomation26.inventoryIndexToMenuSlot(35)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ElytraSwapAutomation26
                        .inventoryIndexToMenuSlot(36)
        );
    }

    private static ElytraSwapDecisionEngine26 ownedEngine(long session) {
        ElytraSwapDecisionEngine26 engine =
                new ElytraSwapDecisionEngine26(TIMING);
        engine.step(observation(session));
        ElytraSwapDecisionEngine26.Decision equip =
                engine.step(observation(session));
        engine.commit(equip, true);
        engine.step(owned(session, false, true));
        engine.step(owned(session, false, true));
        assertEquals(
                ElytraSwapDecisionEngine26.Phase.OWNED,
                engine.snapshot().phase()
        );
        return engine;
    }

    private static ElytraSwapDecisionEngine26.Candidate candidate(
            int slot,
            int remaining,
            boolean cursed,
            boolean hotbar,
            boolean selected
    ) {
        return new ElytraSwapDecisionEngine26.Candidate(
                slot,
                true,
                true,
                remaining,
                cursed,
                hotbar,
                selected
        );
    }

    private static ElytraSwapDecisionEngine26.Observation observation(
            long session
    ) {
        return new ElytraSwapDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                false,
                false,
                2.0,
                1.5,
                12,
                true,
                true,
                false,
                false,
                false,
                false,
                false
        );
    }

    private static ElytraSwapDecisionEngine26.Observation owned(
            long session,
            boolean onGround,
            boolean sourceIntact
    ) {
        return new ElytraSwapDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                onGround,
                false,
                2.0,
                1.5,
                -1,
                true,
                true,
                true,
                true,
                sourceIntact,
                false,
                false
        );
    }

    private static ElytraSwapDecisionEngine26.Observation disabledOwned(
            long session,
            boolean onGround
    ) {
        ElytraSwapDecisionEngine26.Observation owned =
                owned(session, onGround, true);
        return new ElytraSwapDecisionEngine26.Observation(
                owned.sessionKey(),
                false,
                owned.sessionReady(),
                owned.inventoryReady(),
                owned.onGround(),
                owned.unsafeEnvironment(),
                owned.fallDistance(),
                owned.minimumFallDistance(),
                owned.candidateSlot(),
                owned.displacedChestPresent(),
                owned.restoreArmor(),
                owned.wearingAnyElytra(),
                owned.wearingOwnedElytra(),
                owned.sourceOwnershipIntact(),
                owned.restoreConfirmed(),
                owned.ownershipContradicted()
        );
    }

    private static ElytraSwapDecisionEngine26.Observation restored(
            long session
    ) {
        return new ElytraSwapDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                false,
                0.0,
                1.5,
                -1,
                true,
                true,
                false,
                false,
                false,
                true,
                false
        );
    }

    private static ElytraSwapDecisionEngine26.Observation replace(
            ElytraSwapDecisionEngine26.Observation source,
            boolean wearingOwned,
            boolean sourceIntact,
            boolean restored,
            boolean contradicted
    ) {
        return new ElytraSwapDecisionEngine26.Observation(
                source.sessionKey(),
                source.enabled(),
                source.sessionReady(),
                source.inventoryReady(),
                source.onGround(),
                source.unsafeEnvironment(),
                source.fallDistance(),
                source.minimumFallDistance(),
                source.candidateSlot(),
                source.displacedChestPresent(),
                source.restoreArmor(),
                source.wearingAnyElytra(),
                wearingOwned,
                sourceIntact,
                restored,
                contradicted
        );
    }
}
