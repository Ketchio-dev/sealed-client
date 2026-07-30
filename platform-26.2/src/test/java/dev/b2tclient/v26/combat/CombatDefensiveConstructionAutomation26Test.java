package dev.b2tclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombatDefensiveConstructionAutomation26Test {
    @Test
    void defaultsAreConservativeAndSnapshotNeverReportsFalseSuccess() {
        CombatDefensiveConstructionAutomation26 service =
                new CombatDefensiveConstructionAutomation26();
        CombatDefensiveConstructionAutomation26.Configuration configuration =
                service.configuration();

        assertEquals(1, configuration.surround().maximumRetries());
        assertEquals(
                8,
                configuration.surround().confirmationTimeoutTicks()
        );
        assertEquals(
                512,
                configuration.holeFill().maximumHoleScans()
        );
        assertEquals(12.0, configuration.burrow().minimumHealth());
        assertEquals(
                CombatDefensiveConstructionAutomation26.Outcome.IDLE,
                service.snapshot().outcome()
        );
        assertEquals(0L, service.snapshot().confirmedPlacements());
        assertNull(service.snapshot().pendingPosition());
    }

    @Test
    void configurationIsImmutableAndRejectsUnsafeBounds() {
        CombatDefensiveConstructionAutomation26.Configuration defaults =
                CombatDefensiveConstructionAutomation26.Configuration
                        .defaults();
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatDefensiveConstructionAutomation26
                        .AutoTrapConfiguration(
                        Double.NaN,
                        4.5,
                        false,
                        8.0,
                        2,
                        40,
                        8,
                        1,
                        48,
                        64
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatDefensiveConstructionAutomation26
                        .SurroundConfiguration(
                        6.1,
                        true,
                        8.0,
                        2,
                        40,
                        8,
                        1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatDefensiveConstructionAutomation26
                        .HoleFillConfiguration(
                        8.0,
                        4.5,
                        4,
                        3.0,
                        8.0,
                        2,
                        40,
                        8,
                        1,
                        48,
                        1025,
                        64
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new CombatDefensiveConstructionAutomation26
                        .Configuration(
                        null,
                        defaults.holeFill(),
                        defaults.selfTrap(),
                        defaults.autoTrap(),
                        defaults.burrow()
                )
        );
    }

    @Test
    void replacingConfigurationUsesExactValidatedRecord() {
        CombatDefensiveConstructionAutomation26 service =
                new CombatDefensiveConstructionAutomation26();
        CombatDefensiveConstructionAutomation26.Configuration defaults =
                service.configuration();
        CombatDefensiveConstructionAutomation26.Configuration changed =
                new CombatDefensiveConstructionAutomation26.Configuration(
                        new CombatDefensiveConstructionAutomation26
                                .SurroundConfiguration(
                                5.0,
                                false,
                                9.0,
                                3,
                                50,
                                9,
                                2
                        ),
                        defaults.holeFill(),
                        defaults.selfTrap(),
                        defaults.autoTrap(),
                        defaults.burrow()
                );

        service.setConfiguration(changed);
        assertEquals(changed, service.configuration());
        assertThrows(
                NullPointerException.class,
                () -> service.setConfiguration(null)
        );
    }

    @Test
    void simultaneouslyEnabledModesKeepIndependentPolicies() {
        CombatDefensiveConstructionAutomation26.Configuration configuration =
                divergentConfiguration();
        CombatDefensiveConstructionAutomation26 service =
                new CombatDefensiveConstructionAutomation26();
        service.setConfiguration(configuration);

        assertPolicy(
                service,
                CombatDefensiveConstructionAutomation26.ModuleId.SURROUND,
                2.1,
                3.0,
                0,
                11,
                3,
                0,
                0.0
        );
        assertPolicy(
                service,
                CombatDefensiveConstructionAutomation26.ModuleId.HOLE_FILL,
                3.2,
                4.0,
                1,
                22,
                4,
                1,
                7.5
        );
        assertPolicy(
                service,
                CombatDefensiveConstructionAutomation26.ModuleId.SELF_TRAP,
                4.3,
                5.0,
                2,
                33,
                5,
                2,
                0.0
        );
        assertPolicy(
                service,
                CombatDefensiveConstructionAutomation26.ModuleId.AUTO_TRAP,
                5.4,
                6.0,
                3,
                44,
                6,
                3,
                5.5
        );
        assertPolicy(
                service,
                CombatDefensiveConstructionAutomation26.ModuleId.BURROW,
                5.9,
                7.0,
                4,
                55,
                7,
                1,
                0.0
        );
        assertEquals(17, configuration.holeFill().maximumPlayerScans());
        assertEquals(23, configuration.autoTrap().maximumPlayerScans());
        assertEquals(31, configuration.holeFill().maximumFriendEntries());
        assertEquals(37, configuration.autoTrap().maximumFriendEntries());
    }

    @Test
    void replacingOneModeCannotRewriteAnotherMode() {
        CombatDefensiveConstructionAutomation26.Configuration original =
                divergentConfiguration();
        var replacement =
                new CombatDefensiveConstructionAutomation26.Configuration(
                        new CombatDefensiveConstructionAutomation26
                                .SurroundConfiguration(
                                6.0,
                                false,
                                40.0,
                                20,
                                200,
                                40,
                                3
                        ),
                        original.holeFill(),
                        original.selfTrap(),
                        original.autoTrap(),
                        original.burrow()
                );

        assertEquals(original.holeFill(), replacement.holeFill());
        assertEquals(original.selfTrap(), replacement.selfTrap());
        assertEquals(original.autoTrap(), replacement.autoTrap());
        assertEquals(original.burrow(), replacement.burrow());
        assertEquals(3.2, replacement.policy(
                CombatDefensiveConstructionAutomation26.ModuleId.HOLE_FILL
        ).placementRange());
        assertEquals(5.5, replacement.policy(
                CombatDefensiveConstructionAutomation26.ModuleId.AUTO_TRAP
        ).targetRange());
    }

    @Test
    void everyPlacementClaimsUseAndHotbarAsOneAtomicBundle() {
        for (CombatDefensiveConstructionAutomation26.ModuleId module :
                CombatDefensiveConstructionAutomation26.ModuleId.values()) {
            Set<CombatActionArbiter26.Channel> channels =
                    CombatDefensiveConstructionAutomation26
                            .requiredChannels(module);
            assertEquals(true, channels.contains(
                    CombatActionArbiter26.Channel.USE
            ));
            assertEquals(true, channels.contains(
                    CombatActionArbiter26.Channel.HOTBAR
            ));
            assertEquals(
                    module
                            == CombatDefensiveConstructionAutomation26
                            .ModuleId.BURROW,
                    channels.contains(
                            CombatActionArbiter26.Channel.MOVEMENT
                    )
            );
        }
    }

    private static void assertPolicy(
            CombatDefensiveConstructionAutomation26 service,
            CombatDefensiveConstructionAutomation26.ModuleId module,
            double placementRange,
            double minimumHealth,
            int actionDelay,
            int failureDelay,
            int confirmationTicks,
            int retries,
            double targetRange
    ) {
        CombatDefensiveConstructionAutomation26.ModePolicy policy =
                service.policy(module);
        assertEquals(placementRange, policy.placementRange());
        assertEquals(minimumHealth, policy.minimumHealth());
        assertEquals(actionDelay, policy.actionCooldownTicks());
        assertEquals(failureDelay, policy.failureCooldownTicks());
        assertEquals(confirmationTicks, policy.confirmationTimeoutTicks());
        assertEquals(retries, policy.maximumRetries());
        assertEquals(targetRange, policy.targetRange());
    }

    private static CombatDefensiveConstructionAutomation26.Configuration
    divergentConfiguration() {
        return new CombatDefensiveConstructionAutomation26.Configuration(
                new CombatDefensiveConstructionAutomation26
                        .SurroundConfiguration(
                        2.1,
                        true,
                        3.0,
                        0,
                        11,
                        3,
                        0
                ),
                new CombatDefensiveConstructionAutomation26
                        .HoleFillConfiguration(
                        7.5,
                        3.2,
                        2,
                        2.5,
                        4.0,
                        1,
                        22,
                        4,
                        1,
                        17,
                        128,
                        31
                ),
                new CombatDefensiveConstructionAutomation26
                        .SelfTrapConfiguration(
                        4.3,
                        false,
                        5.0,
                        2,
                        33,
                        5,
                        2
                ),
                new CombatDefensiveConstructionAutomation26
                        .AutoTrapConfiguration(
                        5.5,
                        5.4,
                        true,
                        6.0,
                        3,
                        44,
                        6,
                        3,
                        23,
                        37
                ),
                new CombatDefensiveConstructionAutomation26
                        .BurrowConfiguration(
                        5.9,
                        7.0,
                        4,
                        55,
                        7,
                        1,
                        false,
                        19,
                        1.2
                )
        );
    }
}
