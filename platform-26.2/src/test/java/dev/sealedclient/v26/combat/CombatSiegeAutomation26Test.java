package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatSiegeAutomation26Test {
    @Test
    void adapterStartsIdleAndExposesConservativeValidatedConfiguration() {
        CombatSiegeAutomation26 service = new CombatSiegeAutomation26();

        assertEquals(8.0, service.configuration().targetRange());
        assertEquals(5, service.configuration().minimumToolDurability());
        assertEquals("idle", service.snapshot().cityPhase());
        assertEquals("idle", service.snapshot().pistonPhase());
        assertEquals("none", service.snapshot().cleanupPhase());
        assertNull(service.snapshot().cityTarget());
        assertNull(service.snapshot().pistonBase());
        assertThrows(
                NullPointerException.class,
                () -> service.setConfiguration(null)
        );
    }

    @Test
    void cityAndPistonClaimsAreCompleteAtomicBundles() {
        assertEquals(
                Set.of(
                        CombatActionArbiter26.Channel.ATTACK,
                        CombatActionArbiter26.Channel.HOTBAR
                ),
                CombatSiegeAutomation26.requiredChannels(false)
        );
        assertEquals(
                Set.of(
                        CombatActionArbiter26.Channel.ATTACK,
                        CombatActionArbiter26.Channel.USE,
                        CombatActionArbiter26.Channel.HOTBAR,
                        CombatActionArbiter26.Channel.ROTATION
                ),
                CombatSiegeAutomation26.requiredChannels(true)
        );
    }

    @Test
    void pistonBundleCannotPartiallyExecuteAfterArbitrationLoss() {
        CombatActionArbiter26 arbiter = new CombatActionArbiter26();
        arbiter.beginTick(CombatActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                "rotation.guard",
                200,
                Set.of(CombatActionArbiter26.Channel.ROTATION)
        );
        arbiter.submit(
                CombatSiegeAutomation26.PISTON_OWNER,
                95,
                CombatSiegeAutomation26.requiredChannels(true)
        );
        arbiter.resolve();

        assertFalse(arbiter.ownsAll(
                CombatSiegeAutomation26.PISTON_OWNER,
                CombatSiegeAutomation26.requiredChannels(true)
        ));
        assertFalse(arbiter.owns(
                CombatSiegeAutomation26.PISTON_OWNER,
                CombatActionArbiter26.Channel.ATTACK
        ));
        assertFalse(arbiter.owns(
                CombatSiegeAutomation26.PISTON_OWNER,
                CombatActionArbiter26.Channel.HOTBAR
        ));
        assertTrue(arbiter.owns(
                "rotation.guard",
                CombatActionArbiter26.Channel.ROTATION
        ));
    }

    @Test
    void adapterCityStopRemainsLatchedAcrossDeniedArbitration() {
        CombatSiegeAutomation26 service = new CombatSiegeAutomation26();
        CombatActionArbiter26 arbiter = new CombatActionArbiter26();
        service.requestCityStop();

        arbiter.beginTick(CombatActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                "higher.priority",
                100,
                CombatSiegeAutomation26.requiredChannels(false)
        );
        arbiter.submit(
                CombatSiegeAutomation26.CITY_OWNER,
                65,
                CombatSiegeAutomation26.requiredChannels(false)
        );
        arbiter.resolve();
        assertFalse(arbiter.ownsAll(
                CombatSiegeAutomation26.CITY_OWNER,
                CombatSiegeAutomation26.requiredChannels(false)
        ));
        assertTrue(service.cityStopRequested());

        service.release(null);
        assertFalse(service.cityStopRequested());
    }

    @Test
    void adapterRejectsPreexistingCrystalBeforeLiveDiscovery() {
        assertFalse(CombatSiegeAutomation26.acceptsTransactionCrystal(
                Set.of(9, 10),
                9,
                0.0,
                true
        ));
        assertFalse(CombatSiegeAutomation26.acceptsTransactionCrystal(
                Set.of(9, 10),
                11,
                0.0,
                false
        ));
        assertTrue(CombatSiegeAutomation26.acceptsTransactionCrystal(
                Set.of(9, 10),
                11,
                0.1,
                true
        ));
    }

    @Test
    void adapterCleanupPlanningIsPureAndStableWhenGrantIsDenied() {
        CombatSiegeAutomation26 service = new CombatSiegeAutomation26();
        CombatSiegeAutomation26.Snapshot before = service.snapshot();

        assertEquals(
                PistonCrystalDecisionEngine26.CleanupDirective.ABANDON,
                CombatSiegeAutomation26.planCleanup(
                        true,
                        false,
                        true,
                        true
                )
        );
        assertEquals(
                PistonCrystalDecisionEngine26.CleanupDirective.ABANDON,
                CombatSiegeAutomation26.planCleanup(
                        true,
                        false,
                        true,
                        true
                )
        );
        assertEquals(before, service.snapshot());
    }

    @Test
    void adapterRevokesCleanupOwnershipAfterObservedRemoval() {
        PistonCrystalDecisionEngine26.PlacementOwnership ownership =
                PistonCrystalDecisionEngine26.PlacementOwnership
                        .unconfirmed();
        ownership = CombatSiegeAutomation26.observeCleanupOwnership(
                ownership,
                true,
                true
        );
        assertTrue(ownership.owned());

        ownership = CombatSiegeAutomation26.observeCleanupOwnership(
                ownership,
                true,
                false
        );
        assertTrue(ownership.revoked());
        assertFalse(ownership.owned());

        // A same-type replacement cannot reacquire ownership.
        ownership = CombatSiegeAutomation26.observeCleanupOwnership(
                ownership,
                true,
                true
        );
        assertTrue(ownership.revoked());
        assertFalse(ownership.owned());
    }

    @Test
    void adapterUsesStrictOpeningAndReachPolicies() {
        assertFalse(CombatSiegeAutomation26.reflectedCityOpening(
                false,
                false
        ));
        assertTrue(CombatSiegeAutomation26.reflectedCityOpening(
                true,
                false
        ));
        assertTrue(CombatSiegeAutomation26.interactionInRange(
                20.25,
                4.5
        ));
        assertFalse(CombatSiegeAutomation26.interactionInRange(
                Math.nextUp(20.25),
                4.5
        ));
    }

    @Test
    void simultaneousModesKeepIndependentConfigurations() {
        CombatSiegeAutomation26 service = new CombatSiegeAutomation26();
        CombatSiegeAutomation26.CityConfiguration city =
                new CombatSiegeAutomation26.CityConfiguration(
                        4.0,
                        3.5,
                        7.0,
                        9,
                        180,
                        0,
                        2,
                        30
                );
        CombatSiegeAutomation26.PistonConfiguration piston =
                new CombatSiegeAutomation26.PistonConfiguration(
                        12.0,
                        5.5,
                        5.75,
                        15.0,
                        8.0,
                        10.0,
                        2.0,
                        8.0,
                        9.0,
                        11,
                        12,
                        2,
                        9,
                        90,
                        120
                );

        service.setModeConfiguration(
                new CombatSiegeAutomation26.ModeConfiguration(
                        city,
                        piston
                )
        );

        assertEquals(city, service.cityConfiguration());
        assertEquals(piston, service.pistonConfiguration());
        assertNotEquals(
                service.cityConfiguration().targetRange(),
                service.pistonConfiguration().targetRange()
        );
        assertEquals(4.0, service.cityConfiguration().targetRange());
        assertEquals(12.0, service.pistonConfiguration().targetRange());
    }

    @Test
    void changingOneModeNeverMutatesTheOtherMode() {
        CombatSiegeAutomation26 service = new CombatSiegeAutomation26();
        CombatSiegeAutomation26.PistonConfiguration originalPiston =
                service.pistonConfiguration();
        CombatSiegeAutomation26.CityConfiguration city =
                new CombatSiegeAutomation26.CityConfiguration(
                        3.0,
                        2.0,
                        8.0,
                        5,
                        200,
                        1,
                        3,
                        50
                );
        service.setCityConfiguration(city);

        assertEquals(city, service.cityConfiguration());
        assertEquals(originalPiston, service.pistonConfiguration());

        CombatSiegeAutomation26.CityConfiguration originalCity =
                service.cityConfiguration();
        CombatSiegeAutomation26.PistonConfiguration piston =
                new CombatSiegeAutomation26.PistonConfiguration(
                        16.0,
                        6.0,
                        6.0,
                        20.0,
                        10.0,
                        8.0,
                        1.0,
                        10.0,
                        10.0,
                        5,
                        20,
                        0,
                        12,
                        100,
                        150
                );
        service.setPistonConfiguration(piston);

        assertEquals(originalCity, service.cityConfiguration());
        assertEquals(piston, service.pistonConfiguration());
    }

    @Test
    void legacyFlatConfigurationFansOutWithoutBreakingCallers() {
        CombatSiegeAutomation26 service = new CombatSiegeAutomation26();
        CombatSiegeAutomation26.Configuration legacy =
                CombatSiegeAutomation26.Configuration.defaults();
        service.setConfiguration(legacy);

        assertEquals(
                legacy.targetRange(),
                service.cityConfiguration().targetRange()
        );
        assertEquals(
                legacy.targetRange(),
                service.pistonConfiguration().targetRange()
        );
        assertEquals(
                legacy.minimumToolDurability(),
                service.pistonConfiguration()
                        .cleanupMinimumToolDurability()
        );
        assertEquals(legacy, service.configuration());
    }

    @Test
    void independentRangesDriveIndependentPlannersWhenBothEnabled() {
        CombatSiegeAutomation26.CityConfiguration city =
                new CombatSiegeAutomation26.CityConfiguration(
                        4.0,
                        4.5,
                        8.0,
                        5,
                        240,
                        1,
                        4,
                        40
                );
        CombatSiegeAutomation26.PistonConfiguration piston =
                new CombatSiegeAutomation26.PistonConfiguration(
                        10.0,
                        4.5,
                        5.0,
                        12.0,
                        6.0,
                        12.0,
                        4.0,
                        6.0,
                        6.0,
                        5,
                        8,
                        1,
                        6,
                        60,
                        80
                );
        CityBreakerDecisionEngine26.Candidate cityCandidate =
                new CityBreakerDecisionEngine26.Candidate(
                        1L,
                        2,
                        7.0,
                        3.0,
                        true,
                        false,
                        false,
                        true,
                        true,
                        true,
                        1,
                        10.0F
                );
        PistonCrystalDecisionEngine26.Layout pistonLayout =
                new PistonCrystalDecisionEngine26.Layout(
                        2L,
                        2,
                        new PistonCrystalDecisionEngine26.Cell(0, 64, 0),
                        new PistonCrystalDecisionEngine26.Cell(1, 65, 0),
                        new PistonCrystalDecisionEngine26.Cell(1, 66, 0),
                        PistonCrystalDecisionEngine26.Horizontal.WEST,
                        7.0,
                        4.0,
                        true,
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                );

        assertEquals(-1L, CityBreakerDecisionEngine26.selectBest(
                java.util.List.of(cityCandidate),
                new CityBreakerDecisionEngine26.Limits(
                        8,
                        city.targetRange(),
                        city.mineRange()
                )
        ));
        assertEquals(2L, PistonCrystalDecisionEngine26.selectBest(
                java.util.List.of(pistonLayout),
                new PistonCrystalDecisionEngine26.Limits(
                        8,
                        piston.targetRange(),
                        piston.placeRange()
                )
        ));
    }
}
