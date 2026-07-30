package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedAnchorDecisionEngine26Test {
    private static final BedAnchorDecisionEngine26.Limits LIMITS =
            new BedAnchorDecisionEngine26.Limits(
                    8,
                    5.0,
                    12.0,
                    4.0,
                    6.0,
                    6.0,
                    1.35,
                    0.03,
                    0.25
            );

    @Test
    void dimensionPolicyRejectsNormalNetherAnchors() {
        assertFalse(BedAnchorDecisionEngine26.dimensionAllowsExplosion(
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                true,
                null
        ));
        assertTrue(BedAnchorDecisionEngine26.dimensionAllowsExplosion(
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                false,
                null
        ));
    }

    @Test
    void dimensionPolicyAllowsOnlyExplicitlyExplosiveBeds() {
        assertTrue(BedAnchorDecisionEngine26.dimensionAllowsExplosion(
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                null,
                true
        ));
        assertFalse(BedAnchorDecisionEngine26.dimensionAllowsExplosion(
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                null,
                false
        ));
        assertFalse(BedAnchorDecisionEngine26.dimensionAllowsExplosion(
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                null,
                null
        ));
    }

    @Test
    void selectionRejectsFatalSelfAndFriendDamage() {
        long selected = BedAnchorDecisionEngine26.selectBest(
                List.of(
                        candidate(1, 18.0, 15.0, 0.0, false, 0.0),
                        candidate(2, 16.0, 3.0, 5.0, true, 20.0),
                        candidate(3, 12.0, 3.0, 2.0, true, 20.0)
                ),
                LIMITS,
                20.0
        );

        assertEquals(3L, selected);
    }

    @Test
    void selectionFailsClosedForInvalidWorldResourcesAndDimension() {
        assertEquals(-1L, BedAnchorDecisionEngine26.selectBest(
                List.of(
                        candidate(1, false, true, true),
                        candidate(2, true, false, true),
                        candidate(3, true, true, false)
                ),
                LIMITS,
                20.0
        ));
    }

    @Test
    void selectionHonorsFatalReserveAtEquality() {
        assertEquals(-1L, BedAnchorDecisionEngine26.selectBest(
                List.of(candidate(4, 12.0, 4.0, 0.0, false, 0.0)),
                LIMITS,
                10.0
        ));
    }

    @Test
    void readyUseWinsExactTieAndOrderingIsDeterministic() {
        BedAnchorDecisionEngine26.Candidate place = candidate(
                9,
                BedAnchorDecisionEngine26.Action.PLACE,
                10.0,
                2.0
        );
        BedAnchorDecisionEngine26.Candidate use = candidate(
                7,
                BedAnchorDecisionEngine26.Action.USE,
                10.0,
                2.0
        );

        assertEquals(7L, BedAnchorDecisionEngine26.selectBest(
                List.of(place, use),
                LIMITS,
                20.0
        ));
    }

    @Test
    void selectionNeverReadsBeyondBound() {
        BedAnchorDecisionEngine26.Limits oneScan =
                new BedAnchorDecisionEngine26.Limits(
                        1,
                        1.0,
                        20.0,
                        20.0,
                        1.0,
                        1.0,
                        1.0,
                        0.0,
                        0.0
                );
        assertEquals(10L, BedAnchorDecisionEngine26.selectBest(
                List.of(
                        candidate(10, 5.0, 1.0, 0.0, false, 0.0),
                        candidate(1, 30.0, 0.0, 0.0, false, 0.0)
                ),
                oneScan,
                20.0
        ));
    }

    @Test
    void explosionCurveIsFiniteAndMonotonic() {
        double near = BedAnchorDecisionEngine26.rawExplosionDamage(
                1.0,
                1.0,
                5.0
        );
        double far = BedAnchorDecisionEngine26.rawExplosionDamage(
                8.0,
                1.0,
                5.0
        );
        assertTrue(Double.isFinite(near));
        assertTrue(near > far);
        assertEquals(0.0, BedAnchorDecisionEngine26.rawExplosionDamage(
                10.0,
                1.0,
                5.0
        ));
        assertEquals(0.0, BedAnchorDecisionEngine26.rawExplosionDamage(
                1.0,
                Double.NaN,
                5.0
        ));
    }

    @Test
    void restorationNeverOverwritesManualSelection() {
        assertEquals(2, BedAnchorDecisionEngine26.restorationSlot(2, 7, 7));
        assertEquals(-1, BedAnchorDecisionEngine26.restorationSlot(2, 7, 4));
        assertEquals(-1, BedAnchorDecisionEngine26.restorationSlot(-1, 7, 7));
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BedAnchorDecisionEngine26.Limits(
                        0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        1.0
                )
        );
    }

    @Test
    void liveConfigurationIsBoundedAndFinite() {
        CombatBedAnchorAutomation26.Configuration defaults =
                CombatBedAnchorAutomation26.Configuration.defaults();
        assertEquals(4.5, defaults.useRange());
        assertEquals(4.5, defaults.placeRange());
        assertEquals(40, defaults.failureCooldownTicks());

        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatBedAnchorAutomation26.Configuration(
                        10.0,
                        Double.NaN,
                        4.5,
                        5.0,
                        12.0,
                        4.0,
                        6.0,
                        6.0,
                        12.0,
                        2,
                        40
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatBedAnchorAutomation26.Configuration(
                        10.0,
                        4.5,
                        4.5,
                        5.0,
                        12.0,
                        4.0,
                        6.0,
                        6.0,
                        12.0,
                        2,
                        0
                )
        );
    }

    @Test
    void simultaneousModulesUseTheirOwnDamagePolicies() {
        BedAnchorDecisionEngine26.Limits strictAnchor =
                limits(20.0, 12.0, 4.0, 6.0, 8);
        BedAnchorDecisionEngine26.Limits permissiveBed =
                limits(5.0, 12.0, 4.0, 6.0, 8);
        BedAnchorDecisionEngine26.Candidate anchor = candidate(
                1,
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                10.0,
                2.0
        );
        BedAnchorDecisionEngine26.Candidate bed = candidate(
                2,
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                8.0,
                2.0
        );

        assertEquals(2L, BedAnchorDecisionEngine26.selectBest(
                List.of(anchor, bed),
                new BedAnchorDecisionEngine26.Policies(
                        strictAnchor,
                        permissiveBed
                ),
                20.0
        ));
        assertEquals(1L, BedAnchorDecisionEngine26.selectBest(
                List.of(anchor, bed),
                new BedAnchorDecisionEngine26.Policies(
                        limits(5.0, 12.0, 4.0, 6.0, 8),
                        limits(20.0, 12.0, 4.0, 6.0, 8)
                ),
                20.0
        ));
    }

    @Test
    void simultaneousModulesHaveIndependentHealthReservesAndScanBudgets() {
        BedAnchorDecisionEngine26.Limits anchorLimits =
                limits(1.0, 20.0, 20.0, 15.0, 1);
        BedAnchorDecisionEngine26.Limits bedLimits =
                limits(1.0, 20.0, 20.0, 1.0, 1);
        BedAnchorDecisionEngine26.Candidate anchorFirst = candidate(
                10,
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                5.0,
                1.0
        );
        BedAnchorDecisionEngine26.Candidate anchorBeyondBudget = candidate(
                11,
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                30.0,
                0.0
        );
        BedAnchorDecisionEngine26.Candidate bedAfterAnchorBudget = candidate(
                12,
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                8.0,
                1.0
        );

        assertEquals(12L, BedAnchorDecisionEngine26.selectBest(
                List.of(
                        anchorFirst,
                        anchorBeyondBudget,
                        bedAfterAnchorBudget
                ),
                new BedAnchorDecisionEngine26.Policies(
                        anchorLimits,
                        bedLimits
                ),
                12.0
        ));
    }

    @Test
    void serviceStoresAnchorAndBedConfigurationsIndependently() {
        CombatBedAnchorAutomation26 service =
                new CombatBedAnchorAutomation26();
        CombatBedAnchorAutomation26.Configuration anchor =
                configuration(6.0, 3.0, 14.0, 1, 17);
        CombatBedAnchorAutomation26.Configuration bed =
                configuration(14.0, 5.5, 4.0, 9, 73);

        service.setAnchorConfiguration(anchor);
        assertEquals(anchor, service.anchorConfiguration());
        assertEquals(
                CombatBedAnchorAutomation26.Configuration.defaults(),
                service.bedConfiguration()
        );

        service.setBedConfiguration(bed);
        assertEquals(anchor, service.anchorConfiguration());
        assertEquals(bed, service.bedConfiguration());
        assertEquals(
                anchor,
                CombatBedAnchorAutomation26.configurationFor(
                        BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                        anchor,
                        bed
                )
        );
        assertEquals(
                bed,
                CombatBedAnchorAutomation26.configurationFor(
                        BedAnchorDecisionEngine26.ExplosiveKind.BED,
                        anchor,
                        bed
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> service.setAnchorConfiguration(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> service.setBedConfiguration(null)
        );
    }

    @Test
    void simultaneousModulesKeepActionAndFailureDelaysIndependent() {
        CombatBedAnchorAutomation26.ActionCooldowns cooldowns =
                CombatBedAnchorAutomation26.ActionCooldowns.zero()
                        .with(
                                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                                17
                        );
        assertEquals(17, cooldowns.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
        ));
        assertEquals(0, cooldowns.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.BED
        ));

        cooldowns = cooldowns.with(
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                73
        );
        assertEquals(17, cooldowns.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
        ));
        assertEquals(73, cooldowns.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.BED
        ));

        CombatBedAnchorAutomation26.ActionCooldowns ticked =
                cooldowns.tick();
        assertEquals(16, ticked.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
        ));
        assertEquals(72, ticked.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.BED
        ));
    }

    private static BedAnchorDecisionEngine26.Candidate candidate(
            long key,
            double targetDamage,
            double selfDamage,
            double friendDamage,
            boolean friendPresent,
            double friendHealth
    ) {
        return new BedAnchorDecisionEngine26.Candidate(
                key,
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                BedAnchorDecisionEngine26.Action.USE,
                targetDamage,
                selfDamage,
                friendDamage,
                friendPresent,
                friendHealth,
                3.0,
                true,
                true,
                true
        );
    }

    private static BedAnchorDecisionEngine26.Candidate candidate(
            long key,
            BedAnchorDecisionEngine26.ExplosiveKind kind,
            double targetDamage,
            double selfDamage
    ) {
        return new BedAnchorDecisionEngine26.Candidate(
                key,
                kind,
                BedAnchorDecisionEngine26.Action.USE,
                targetDamage,
                selfDamage,
                0.0,
                false,
                0.0,
                3.0,
                true,
                true,
                true
        );
    }

    private static BedAnchorDecisionEngine26.Limits limits(
            double minimumDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfReserve,
            int scans
    ) {
        return new BedAnchorDecisionEngine26.Limits(
                scans,
                minimumDamage,
                maximumSelfDamage,
                maximumFriendDamage,
                selfReserve,
                1.0,
                1.0,
                0.0,
                0.0
        );
    }

    private static CombatBedAnchorAutomation26.Configuration configuration(
            double targetRange,
            double useRange,
            double minimumHealth,
            int actionDelay,
            int failureDelay
    ) {
        return new CombatBedAnchorAutomation26.Configuration(
                targetRange,
                useRange,
                useRange,
                5.0,
                12.0,
                4.0,
                6.0,
                6.0,
                minimumHealth,
                actionDelay,
                failureDelay
        );
    }

    private static BedAnchorDecisionEngine26.Candidate candidate(
            long key,
            boolean dimension,
            boolean resources,
            boolean world
    ) {
        return new BedAnchorDecisionEngine26.Candidate(
                key,
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                BedAnchorDecisionEngine26.Action.PLACE,
                12.0,
                2.0,
                0.0,
                false,
                0.0,
                3.0,
                dimension,
                resources,
                world
        );
    }

    private static BedAnchorDecisionEngine26.Candidate candidate(
            long key,
            BedAnchorDecisionEngine26.Action action,
            double targetDamage,
            double selfDamage
    ) {
        return new BedAnchorDecisionEngine26.Candidate(
                key,
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                action,
                targetDamage,
                selfDamage,
                0.0,
                false,
                0.0,
                3.0,
                true,
                true,
                true
        );
    }
}
