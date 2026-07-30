package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalDecisionEngine26Test {
    private static final CrystalDecisionEngine26.Limits LIMITS =
            new CrystalDecisionEngine26.Limits(
                    8,
                    5.5,
                    12.0,
                    4.0,
                    6.0,
                    6.0,
                    1.35,
                    0.03
            );

    @Test
    void selectionRejectsSelfLethalAndFriendUnsafeCandidates() {
        long selected = CrystalDecisionEngine26.selectBest(
                List.of(
                        candidate(1, 20.0, 15.0, 0.0, false, 0.0),
                        candidate(2, 18.0, 4.0, 5.0, true, 20.0),
                        candidate(3, 12.0, 3.0, 2.0, true, 20.0)
                ),
                LIMITS,
                20.0
        );

        assertEquals(3L, selected);
    }

    @Test
    void selectionPreservesHealthReserveAndFailsClosedForLowFriendHealth() {
        assertEquals(-1L, CrystalDecisionEngine26.selectBest(
                List.of(candidate(4, 15.0, 4.0, 0.0, false, 0.0)),
                LIMITS,
                10.0
        ));
        assertEquals(-1L, CrystalDecisionEngine26.selectBest(
                List.of(candidate(5, 15.0, 2.0, 2.0, true, 7.0)),
                LIMITS,
                20.0
        ));
    }

    @Test
    void selectionIsDeterministicAndHonorsScanBound() {
        CrystalDecisionEngine26.Limits twoScans =
                new CrystalDecisionEngine26.Limits(
                        2,
                        1.0,
                        20.0,
                        20.0,
                        1.0,
                        1.0,
                        1.0,
                        0.0
                );
        assertEquals(7L, CrystalDecisionEngine26.selectBest(
                List.of(
                        candidate(9, 10.0, 2.0, 0.0, false, 0.0),
                        candidate(7, 10.0, 2.0, 0.0, false, 0.0),
                        candidate(1, 40.0, 0.0, 0.0, false, 0.0)
                ),
                twoScans,
                20.0
        ));
    }

    @Test
    void explosionCurveIsFiniteBoundedAndMonotonic() {
        double near = CrystalDecisionEngine26.rawExplosionDamage(
                1.0,
                1.0,
                6.0
        );
        double far = CrystalDecisionEngine26.rawExplosionDamage(
                8.0,
                1.0,
                6.0
        );

        assertTrue(Double.isFinite(near));
        assertTrue(near > far);
        assertEquals(0.0, CrystalDecisionEngine26.rawExplosionDamage(
                12.0,
                1.0,
                6.0
        ));
        assertEquals(0.0, CrystalDecisionEngine26.rawExplosionDamage(
                Double.NaN,
                1.0,
                6.0
        ));
    }

    @Test
    void configurationRejectsOutOfRangeCombatValues() {
        CombatCrystalMineAutomation26.Configuration defaults =
                CombatCrystalMineAutomation26.Configuration.defaults();
        assertEquals(4.5, defaults.breakRange());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatCrystalMineAutomation26.Configuration(
                        10.0,
                        9.0,
                        4.5,
                        4.5,
                        5.5,
                        12.0,
                        4.0,
                        6.0,
                        6.0,
                        12.0,
                        8.0,
                        2,
                        40,
                        3
                )
        );
    }

    private static CrystalDecisionEngine26.Candidate candidate(
            long key,
            double targetDamage,
            double selfDamage,
            double friendDamage,
            boolean friendPresent,
            double friendHealth
    ) {
        return new CrystalDecisionEngine26.Candidate(
                key,
                targetDamage,
                selfDamage,
                friendDamage,
                friendPresent,
                friendHealth,
                3.0,
                true
        );
    }
}
